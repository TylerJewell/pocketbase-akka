# pocketbase-akka

Tells open connections about changes to stored records, and decides for each connection
separately whether it is allowed to hear about each change.

A port of [pocketbase/pocketbase](https://github.com/pocketbase/pocketbase) onto **Akka**,
built with **Akka Specify**.

---

## Where it came from

PocketBase is a single-file backend that stores records, serves them over a web interface
and a web address, and pushes changes to connected clients as they happen. It was ported to
derive a specification format precise enough to regenerate a system on a different stack —
the port is the vehicle, the specification is the deliverable.

The part rebuilt here is the pushing and the deciding: which open connections are told about
a record that just changed, and what each of them is told. The specifications the port was
generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `pocketbase-port/`.

---

## pocketbase/pocketbase → this port

📉 1,353 Go lines → **1,191 Java lines**<br>
📁 6 files → **27 files**<br>
⚡ 6,595 nanoseconds → **21 nanoseconds**, to decide one connection against one change<br>
⚡ 443 microseconds → **1.4 microseconds**, for one change reaching ten connections<br>
🎯 27 of 27 messages → **27 of 27 messages** identical, across 8 workloads<br>
🧪 not measured → **60 tests**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/pocketbase-port/bench/REPORT.md).

The speed difference is the price of a smaller job, not a faster version of the same one.
PocketBase asks its database whether the changed record still matches the rule, once for
every connection, which is what lets a rule reach records other than the one that changed.
This port answers the rule from the changed record alone and cannot follow those links.

---

## What it took to build

⏱️ **20.8 hours** from the first command to the published repository, **1.5** of them active<br>
💬 **453** exchanges with the model<br>
✍️ **473,596** tokens written by the model, **83,879,564** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **60** tests

```bash
python toolkit/tokens.py --port pocketbase    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A connection names what it wants to hear about, and one change is announced under six
  names.** A change to a record is offered to anything listening for that one record, for
  everything in its collection, or for the collection by its short name or its long one, so
  a listener chooses how wide a net to cast.
- **A rule with no value set at all lets nobody through except an administrator, and a rule
  set to an empty value lets everybody through.** Those are different settings and the
  difference decides who hears anything, so nothing in this port is allowed to treat one as
  the other.
- **The rule is asked once for every listener, not once for every change.** Two connections
  listening for the same thing get different answers from the same change when the rule
  reads something about who is asking.
- **A listener can narrow further, and can ask for less.** It may attach a condition of its
  own, which has to pass on top of the collection's rule, and a list of the fields it wants,
  which trims what it is sent.
- **A deletion is decided on the values the record held on the way out.** After the record
  is gone the rule would have nothing to read, so the values it held immediately before are
  what the rule is given.
- **A deletion that does not go through tells nobody anything.** The record carries on being
  announced on later changes as though the attempt had never been made.

---

## Design decisions

**Journal instead of a held-back message.** PocketBase writes the message for a deletion
before the record goes, keeps it aside, and hands it over only once the deletion succeeds.
Here a change is written down first and only a change that went through is written down at
all, so there is nothing to keep aside and nothing to throw away.

**Rules read in memory.** PocketBase turns a rule into a database question and asks it once
for each listener, which is slow and can reach records other than the one that changed. Here
the rule is read straight against the changed record, which is far quicker and is why a rule
that reaches other records is not supported.

**Nothing is remembered for a connection that drops.** PocketBase labels every message with
the connection's own name rather than a position, so a returning client has nothing to say
where it left off. Here a client that comes back starts empty and says again what it wants
to hear about, which is the same thing PocketBase's clients do.

**Connections are known only to the machine holding them.** A connection is a socket held
open on one machine, and there is no way to write into one from another. Here the list of
connections is kept per machine, so a deployment across several needs each client's requests
to keep landing on the machine holding its connection.

**Rules and patterns are read once and kept.** The same handful of rule text is read again
for every listener on every change. Here a rule is turned into a form the program can follow
the first time it is seen and that form is kept, so the second listener costs almost nothing.

---

## Running it — the short path

You do not need Java, Maven, or the Akka command-line tool installed. Akka Specify installs
them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/pocketbase-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9057.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

There is no model provider to configure. Nothing here calls a language model.

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9057**.

### Try it

Open a connection, and keep it open. The first line back names the connection:

```bash
curl -N http://localhost:9057/api/realtime
```

Describe a collection, with a rule that lets everybody through:

```bash
curl -X POST http://localhost:9057/api/collections \
  -H 'Content-Type: application/json' \
  -d '{"name":"notes","id":"notes-id","type":"base",
       "fields":[{"name":"title","type":"text"}],
       "listRule":"","viewRule":""}'
```

Tell the connection what to listen for, using the name it was given:

```bash
curl -X POST http://localhost:9057/api/realtime \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"<the name from the first line>","subscriptions":["notes/*"]}'
```

Write a record. It appears on the open connection:

```bash
curl -X POST http://localhost:9057/api/collections/notes/records \
  -H 'Content-Type: application/json' \
  -d '{"id":"note-one","fields":{"title":"first"}}'
```

### Run the tests

```bash
mvn verify
```

45 tests run without starting anything, and 15 more start the service and talk to it over a
socket.

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| none | — | Nothing about this service is set from outside. The listening port is in `src/main/resources/application.conf`. |

---

## Where it differs from pocketbase/pocketbase

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **What a rule can reach.** PocketBase turns a rule into a database question, so a rule may
  read fields of other records through a link — the owner of a related row, for instance.
  This port reads the rule against the changed record and what the request carries, and
  refuses any rule naming something else, because reading in memory is what the whole speed
  difference above comes from and following links would give it back.
- **Who a caller is.** PocketBase issues and checks a signed token. This port takes a header
  naming an existing record — `Authorization: Record <collection>/<recordId>` — and treats
  that record as the caller, because what was being rebuilt is what a rule does with a
  caller, not how a caller proves who they are.
- **The order of the messages one change produces for one connection.** A change is
  announced under six names, and a connection listening under several of them gets several
  messages. PocketBase walks those six in an order its language does not fix; over four runs
  it produced the same order every time. This port fixes the order — the single record
  first, then the whole collection, then the short-name forms — because an order that is
  stable is one a client can be written against.
- **What is in each message.** PocketBase also sends the collection's name and identifier
  and the times the record was created and last changed. This port sends the record's own
  fields and its identifier, because the extra four are properties of PocketBase's storage
  and this port's storage does not have the same ones.
- **What a rejected request says.** PocketBase answers a bad request to listen with an
  object naming the field that failed and a general sentence above it. This port answers
  with one sentence and the same status code, because the sentence is what a person reads
  and the field name is already in the request they sent.
- **What an unknown record does to a write.** PocketBase answers a write aimed at a record
  that is not there with "not found" and one aimed at a record that already exists with "bad
  request". This port does the same, and the two were only lined up after the port had been
  reviewed — before that both came back as "bad request".
- **What a returning connection is told it missed.** Nothing, on both sides. PocketBase has
  no way to say where a connection left off, and neither does this port; a client that comes
  back must say again what it wants to hear about. Listed because a reader could reasonably
  expect a durable version of this to exist here and it does not.
- **Running on more than one machine.** PocketBase is one process, so the question does not
  arise there. Here the list of open connections is per machine, so a client's requests must
  keep landing on the machine holding its connection.
- **How big a stored value may be.** PocketBase checks each field against limits set when
  the collection was described. This port stores whatever was sent, up to the limit its own
  platform enforces on a record, because describing a collection's storage limits is part of
  administering collections and that was not rebuilt here.
- **Comparing a number against a value written in quotes.** Where one side of a comparison
  is a number field and the other is a value written in quotes, this port compares them as
  text. `not checked` — the original's answer for that exact pairing was never run, and it
  is the one line of the comparison rules that rests on a choice rather than a measurement.
- **Everything else the original does around a message.** Expanding linked records on
  request, revealing hidden fields to an administrator, and the email-visibility rules on
  accounts all run inside PocketBase's own announcing step and have no counterpart here.
  They were never attempted and are not compared.

---

## Licence

pocketbase/pocketbase is under the MIT Licence, © 2022 – present, Gani Georgiev. This port
reimplements the behaviour without copied source; see
[`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
