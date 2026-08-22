# Acknowledgements

This project is a port of **[pocketbase/pocketbase](https://github.com/pocketbase/pocketbase)**.

## Licence and copyright

`pocketbase/pocketbase` is under **the MIT Licence**, © 2022 – present, Gani Georgiev. Read
from the repository's own `LICENSE.md` at commit `5d217dd`, not from a badge.

The MIT Licence permits use, modification and redistribution, and requires that the
copyright notice and the permission notice travel with any substantial portion of the
software. No substantial portion travels with this project, because none was copied.

## Was anything copied verbatim?

**No source was copied.** No Go file, no fragment of one, and no generated file from
`pocketbase/pocketbase` appears in `pocketbase-akka`. The rebuild is Java written against a
specification, and the specification was written from running the original rather than from
transcribing it.

One thing came across as a *value*, and it is named here rather than left implicit: the two
record identifiers `4q1xlclmfloku33` and `sywbhecnh46rhm0` appear in `pocketbase-akka`'s
`BenchAnswersTest` and in the probes under `probes/`. They are the identifiers of two rows
in PocketBase's own test fixture database, and they are there so that the two sides of the
benchmark ask about the same caller. They are identifiers, not code.

**No message text was copied either.** The port's refusal messages were checked against the
original's by running it (question log S12): PocketBase answers a bad subscribe call with a
structured body naming the field that failed, and this port answers with a single sentence.
The two are listed in the published README as a difference.

The original is cloned beside this repository so it can be read and run. It is listed in
`.gitignore` and is never edited.

## Is behaviour derived?

**Yes, and that is what this project is.** Every rule in `specs/SPEC-001-pocketbase.md` —
the six topic forms, the meaning of a null rule against a blank one, the operators of the
rule language, the per-subscription filter and field list, and the create, update, delete
and failed-delete lifecycle — was established by running `pocketbase/pocketbase` and
writing down what it did. The behaviour is PocketBase's; the implementation is not.

## What licence that forces on this project

None beyond the original's own terms, since no MIT-licensed text is redistributed here. The
attribution above is given because the behaviour is derived, which is a matter of credit
rather than of licence.

## Also used

- **Akka** — the platform this port is built on.
- **Jackson** — reads and writes the JSON on the wire, by way of the Akka SDK.
- **JUnit 5** and **AssertJ** — the tests.
