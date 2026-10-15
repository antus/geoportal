// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.upload;

import static dev.geoportal.ext.catalog.geodata.upload.UploadedDatasets.EXTENSION;
import static dev.geoportal.ext.catalog.geodata.upload.UploadedDatasets.TYPE;
import static dev.geoportal.ext.catalog.geodata.upload.UploadedDatasets.ref;

import dev.geoportal.ext.catalog.geodata.access.AccessRules;
import dev.geoportal.ext.catalog.geodata.editing.LayerEditing;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.files.FileContent;
import dev.mosaikit.kernel.api.files.FileStorage;
import dev.mosaikit.kernel.api.permission.AccessDeniedException;
import dev.mosaikit.kernel.api.permission.Permissions;
import dev.mosaikit.kernel.api.quota.Quota;
import dev.mosaikit.kernel.api.quota.QuotaExceededException;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import dev.mosaikit.kernel.api.sharing.Sharing;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * The uploaded data of the organization (GP-936), under /api/v1/ext/catalog-geodata/uploads: the datasets
 * the person sees, the upload (permission {@code upload-data}), and rename, replace, download and
 * delete, each at the sharing level it needs.
 *
 * <table>
 *   <caption>Levels of the type {@code dataset}</caption>
 *   <tr><th>Level</th><th>Allows</th></tr>
 *   <tr><td>view</td><td>see the dataset in the catalog (and, with GP-937, its layer)</td></tr>
 *   <tr><td>download</td><td>download the file</td></tr>
 *   <tr><td>edit</td><td>change title and description</td></tr>
 *   <tr><td>edit-data</td><td>replace the content with a new file</td></tr>
 *   <tr><td>manage</td><td>delete the dataset, and share it at every level</td></tr>
 * </table>
 */
@Path("/api/v1/ext/catalog-geodata/uploads")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class UploadResource {

    static final String UPLOAD_DATA = "upload-data";

    private static final Logger LOG = Logger.getLogger(UploadResource.class);

    private final UploadedDatasets datasets;
    private final UploadedDataRepository uploads;
    private final Samples samples;
    private final FileStorage files;
    private final Sharing sharing;
    private final Permissions permissions;
    private final AuditLog audit;
    private final AccessRules access;
    private final LayerEditing editing;

    public UploadResource(UploadedDatasets datasets, UploadedDataRepository uploads, Samples samples,
            FileStorage files, Sharing sharing, Permissions permissions, AuditLog audit, AccessRules access,
            LayerEditing editing) {
        this.access = access;
        this.editing = editing;
        this.datasets = datasets;
        this.uploads = uploads;
        this.samples = samples;
        this.files = files;
        this.sharing = sharing;
        this.permissions = permissions;
        this.audit = audit;
    }

    /**
     * The space of the organization, whether the person may upload and publish (GP-114), and the
     * formats accepted.
     */
    public record Space(long used, Long limit, boolean canUpload, List<String> formats, boolean canPublish,
            long maxFile) {}

    /** What a person may change of a dataset without replacing its content. */
    public record Description(String title, String description) {}

    /** The uploaded datasets the person sees, by title. */
    @GET
    @Transactional
    public List<UploadView> list() {
        try {
            samples.loadIfDue();
        } catch (RuntimeException e) {
            // The samples have a transaction of their own: whatever failed in them rolled back alone, and
            // they are tried again at the next visit, while the data of the organization are listed (#50).
            LOG.warnf(e, "The sample datasets were not loaded, tried again at the next visit: %s", e.getMessage());
        }
        Set<UUID> visible = sharing.visible(EXTENSION, TYPE, "view").stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());
        if (visible.isEmpty()) {
            return List.of();
        }
        List<UploadedData> found = uploads.byIds(visible);
        found.forEach(d -> datasets.advance(d, edits(d)));
        // A dataset shared or unshared in the dialog of Mosaikit becomes GeoFence rules (GP-335).
        access.ensureCurrent();
        return found.stream().map(this::view).toList();
    }

    @GET
    @Path("space")
    @Transactional
    public Space space() {
        Quota quota = datasets.space();
        return new Space(quota.used(), quota.limit().isPresent() ? quota.limit().getAsLong() : null,
                permissions.has(EXTENSION, UPLOAD_DATA),
                Arrays.stream(Format.values()).map(Format::title).toList(),
                permissions.has(EXTENSION, PublicationResource.PUBLISH), UploadLimit.bytes());
    }

    /**
     * Uploads a file as a new dataset: the content is the body, its name and the readable title are
     * in the query. Answers 403 without the permission {@code upload-data} or beyond the storage
     * quota of the organization, and 400 for a format the catalog does not load.
     */
    @POST
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response upload(@QueryParam("name") String name, @QueryParam("title") String title,
            @QueryParam("description") String description, @QueryParam("crs") String crs,
            @QueryParam("layer") String layer, @HeaderParam("Content-Length") Long length, InputStream content) {
        if (!permissions.has(EXTENSION, UPLOAD_DATA)) {
            attempt("dataset.upload-refused", AuditLog.Outcome.DENIED, name, "permission");
            permissions.require(EXTENSION, UPLOAD_DATA);
        }
        try {
            UploadedData data = datasets.upload(name, title, description, content, size(length), false, crs, layer);
            return Response.status(Response.Status.CREATED).entity(view(data)).build();
        } catch (QuotaExceededException e) {
            attempt("dataset.upload-refused", AuditLog.Outcome.FAILED, name, "quota");
            throw e;
        } catch (UploadLimit.TooLarge e) {
            attempt("dataset.upload-refused", AuditLog.Outcome.FAILED, name, "size");
            throw tooLarge(e);
        } catch (UploadedDatasets.UploadRefused e) {
            throw refused(e);
        }
    }

    @GET
    @Path("{id}")
    @Transactional
    public UploadView one(@PathParam("id") UUID id) {
        sharing.require(ref(id), "view");
        UploadedData data = find(id);
        datasets.advance(data, edits(data));
        return view(data);
    }

    /** Renames the dataset and changes its description (level edit); its technical name stays. */
    @PATCH
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public UploadView describe(@PathParam("id") UUID id, Description body) {
        sharing.require(ref(id), "edit");
        if (body == null) {
            throw refused(new UploadedDatasets.UploadRefused("Give a title, a description or both."));
        }
        if (body.title() != null && body.title().strip().length() > 200) {
            throw refused(new UploadedDatasets.UploadRefused("The title has at most 200 characters."));
        }
        return view(datasets.describe(find(id), body.title(), body.description()));
    }

    /** Replaces the content of the dataset with a new file (level edit-data): a new version. */
    @PUT
    @Path("{id}/content")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public UploadView replace(@PathParam("id") UUID id, @QueryParam("name") String name,
            @QueryParam("crs") String crs, @QueryParam("layer") String layer, @HeaderParam("Content-Length") Long length,
            InputStream content) {
        sharing.require(ref(id), "edit-data");
        // The source data of a published layer: the editing limits of GP-036 too.
        editing.requireSourceWrite(id);
        UploadedData data = find(id);
        try {
            return view(datasets.replace(data, name, content, size(length), crs, layer));
        } catch (UploadLimit.TooLarge e) {
            throw tooLarge(e);
        } catch (UploadedDatasets.UploadRefused e) {
            throw refused(e);
        } catch (SecurityException e) {
            throw new AccessDeniedException(
                    "Only the owner of the file of the dataset or an administrator replaces it for now.");
        }
    }

    /**
     * Loads the current file of the dataset again (level edit-data, GP-937): when the geodata database
     * was not available, or with {@code crs}, the EPSG code of a file that states no reference system
     * (GP-324). The answer is the dataset, loaded or failed with the reason.
     */
    @POST
    @Path("{id}/load")
    @Transactional
    public UploadView reload(@PathParam("id") UUID id, @QueryParam("crs") String crs, @QueryParam("layer") String layer) {
        sharing.require(ref(id), "edit-data");
        editing.requireSourceWrite(id);
        try {
            return view(datasets.reload(find(id), crs, layer));
        } catch (UploadedDatasets.UploadRefused e) {
            throw refused(e);
        }
    }

    /** The file of the dataset (level download). */
    @GET
    @Path("{id}/content")
    @Produces(MediaType.WILDCARD)
    @Transactional
    public Response download(@PathParam("id") UUID id) {
        sharing.require(ref(id), "download");
        UploadedData data = find(id);
        FileContent content = files.read(EXTENSION, data.fileId());
        audit.record(EXTENSION, new AuditLog.Entry("dataset.downloaded", TYPE + "/" + id,
                AuditLog.Outcome.SUCCEEDED, Map.of("title", data.title(), "version", data.version())));
        StreamingOutput body = output -> {
            try (content) {
                content.stream().transferTo(output);
            }
        };
        return Response.ok(body, data.contentType())
                .header("Content-Length", data.size())
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + URLEncoder.encode(data.fileName(), StandardCharsets.UTF_8).replace("+", "%20"))
                .build();
    }

    /** Deletes the dataset with its file and its grants (level manage). */
    @DELETE
    @Path("{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        sharing.require(ref(id), "manage");
        try {
            datasets.delete(find(id));
        } catch (SecurityException e) {
            throw new AccessDeniedException(
                    "Only the owner of the file of the dataset or an administrator deletes it for now.");
        }
        return Response.noContent().build();
    }

    /** The levels of {@code dataset} that edit it (manifest: {@code edits: true}). */
    private static final Set<String> EDITING = Set.of("edit", "edit-data", "manage");

    /**
     * Whether the person may take over the outcome of a loading job (GP-937): the owner or a person
     * with a level that edits the dataset. Whoever takes it over stores the COG and keeps full access
     * to that file, so a person who only views the dataset never does.
     */
    private boolean edits(UploadedData data) {
        return sharing.level(ref(data.id())).filter(EDITING::contains).isPresent();
    }

    private UploadedData find(UUID id) {
        return uploads.byId(id).orElseThrow(NotFoundException::new);
    }

    private UploadView view(UploadedData data) {
        ResourceRef ref = ref(data.id());
        return UploadView.of(data, sharing.level(ref).orElse("view"));
    }

    private static long size(Long length) {
        return length == null ? -1 : length;
    }

    private void attempt(String action, AuditLog.Outcome outcome, String name, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("file", name == null ? "" : name);
        details.put("reason", reason);
        audit.record(EXTENSION, new AuditLog.Entry(action, null, outcome, details));
    }

    /** 413 with the largest file accepted, in bytes as {@code maxFile}, which the page says in its language. */
    private static WebApplicationException tooLarge(UploadLimit.TooLarge e) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", "Content Too Large");
        problem.put("status", 413);
        problem.put("detail", e.getMessage());
        problem.put("maxFile", e.limit());
        return new WebApplicationException(e.getMessage(), Response.status(413)
                .type("application/problem+json")
                .entity(problem)
                .build());
    }

    /** A problem detail with the reason of the refusal, which the page shows as it is. */
    private static WebApplicationException refused(RuntimeException e) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", "Bad Request");
        problem.put("status", 400);
        problem.put("detail", e.getMessage());
        return new WebApplicationException(e.getMessage(), Response.status(Response.Status.BAD_REQUEST)
                .type("application/problem+json")
                .entity(problem)
                .build());
    }
}
