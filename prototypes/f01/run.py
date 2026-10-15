# SPDX-FileCopyrightText: 2026 Massimo Antonini
"""GP-327 F01: runs every proof and prints the table of verdicts.

    python run.py                 the stack is up and set up (stack.py up, setup.py)
    python run.py --up --down     from nothing: start the stack, set it up, run, stop and delete it
    python run.py 1 3             only some proofs

Exit code 0 when the proofs 1-5 hold; proof 6 (the Importer) is a measure whose answer is
expected to be "no" for some formats, and is reported, not enforced.
"""
import sys
import time
import traceback

import p1_header
import p2_auth_request
import p3_geofence
import p4_authkey
import p5_roles
import p6_importer

PROOFS = {
    "1": ("Header authentication only from the gateway", p1_header.proof),
    "2": ("nginx auth_request on the Mosaikit session", p2_auth_request.proof),
    "3": ("GeoFence rules by REST (area, CQL, hidden column, deny by default)", p3_geofence.proof),
    "4": ("AuthKey with the web service mapper on the proof stub", p4_authkey.proof),
    "5": ("Role service reads roles from Mosaikit (JDBC on views; REST fallback)", p5_roles.proof),
    "6": ("Importer loads vector files into PostGIS", p6_importer.proof),
}


def main(argv):
    up, down = "--up" in argv, "--down" in argv
    wanted = [a for a in argv if a in PROOFS] or list(PROOFS)
    if up:
        import setup
        import stack
        stack.main("up")
        setup.main()
    results = {}
    t0 = time.time()
    try:
        for number in wanted:
            title, proof = PROOFS[number]
            started = time.time()
            try:
                holds = proof()
            except Exception:                       # a broken proof is a "no", with its trace
                traceback.print_exc()
                holds = False
            results[number] = (title, holds, round(time.time() - started))
    finally:
        if down:
            import stack
            stack.main("down")
    print("\n| Proof | What | Verdict | Seconds |\n|---|---|---|---|")
    for number, (title, holds, seconds) in results.items():
        print(f"| {number} | {title} | {'yes' if holds else 'no'} | {seconds} |")
    print(f"\nall proofs in {round(time.time() - t0)} s")
    required = [holds for number, (_, holds, _) in results.items() if number != "6"]
    return 0 if all(required) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
