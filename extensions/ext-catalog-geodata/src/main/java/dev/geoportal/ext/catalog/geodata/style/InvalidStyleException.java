// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.style;

import java.util.List;
import java.util.stream.Collectors;

/** A style document that is not one of the grammar geoportal/style/v1, with what is wrong in it. */
public class InvalidStyleException extends IllegalArgumentException {

    private final transient List<Problem> problems;

    public InvalidStyleException(List<Problem> problems) {
        super("The style is not valid: " + problems.stream().map(Problem::toString).collect(Collectors.joining("; ")));
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }

    /**
     * Something wrong in a document.
     *
     * @param path where, as a JSON pointer ({@code /style/fill}); empty for the document
     * @param code what, as the editor of the catalog names it: object, grammar, enum, unknown, required,
     *     color, number, field, driver, count, breaks, from, duplicate, scalar, string, boolean, text,
     *     template, dash, rules
     * @param message what, in English
     */
    public record Problem(String path, String code, String message) {

        @Override
        public String toString() {
            return (path.isEmpty() ? "/" : path) + ": " + message;
        }
    }
}
