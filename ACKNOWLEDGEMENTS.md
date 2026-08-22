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

`python toolkit/copied_strings.py pocketbase` pulls every string of ten characters or more
out of `pocketbase-akka` and looks for it verbatim in the clone. Twenty-two occur in both.
None of them is code, and all twenty-two fall into three groups, named here in full rather
than summarised.

**The wire vocabulary — nineteen of the twenty-two, and they match on purpose.** A port of a
protocol that renamed the protocol would not be one. `/api/realtime`, `/api/collections`,
`PB_CONNECT`, `Authorization`, `X-Forwarded-For`, `clientId`, `subscriptions`,
`collectionName`, `collection` and `_superusers` — and the two fragments the tool reports
separately because they carry their punctuation, `/api/collections/` and `"clientId":"` —
are the names a client and a rule already
use, and the rule fragments `@request.auth.id != ''`, `title ~ 'test1'`, `total != 4`,
`active = true` and `active = false` are sentences in PocketBase's rule language, which this
port implements. They were written from the specification, which was written from running
the original.

**Four record identifiers**, `4q1xlclmfloku33`, `sywbhecnh46rhm0`, `llvuca81nly1qls` and
`sz5l5z67tg7gku0`. They name rows in PocketBase's own test fixture database and appear in
`pocketbase-akka`'s tests and in the probes under `probes/`, so that both sides of a
comparison are asking about the same record and the same caller. They are identifiers, not
code.

**Two coincidences**, `an expression` and `empty rule`, both inside `Rule.java`'s error
messages. Ordinary English phrases that the original also happens to contain.

**No message text was copied.** The port's refusal messages were checked against the
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
