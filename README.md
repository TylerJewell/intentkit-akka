# intentkit-akka

Runs a team's saved instructions on a schedule, hands each one to the right agent, and
never lets the same instruction run twice at once.

A port of [crestalnetwork/intentkit](https://github.com/crestalnetwork/intentkit) onto
**Akka**, built with **Akka Specify**.

---

## Where it came from

intentkit is a platform for building AI agents that can act on their own. It was ported to
derive a specification format precise enough to regenerate a system on a different stack —
the port is the vehicle, the specification is the deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `intentkit-port/`.

---

## intentkit → this port

📉 1,468 Python lines → **1,684 Java lines**<br>
📁 9 files → **28 files**<br>
⚡ 15.91 ms → **4.30 ms** for one run, from claimed to recorded<br>
⚡ 4.17 ms → **30.78 ms** to save a new instruction<br>
⚡ 1.32 ms → **14.81 ms** to read one back<br>
🖥️ 2 processes → **1 process**<br>
🎯 71 questions asked of both, **71 answered the same**<br>
🕒 how late a run is → **32.3 ms**, worst of three; intentkit does not record it<br>
🧾 4 saved schedules that can never run → **0**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/intentkit-port/bench/REPORT.md).

---

## What it took to build

⏱️ **2.9 hours** from the first command to the published repository, **2.6** of them active<br>
💬 **490** exchanges with the model<br>
✍️ **540,342** tokens written by the model, **172,034,006** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **73** tests, plus 17 deliberate breakages to check the tests notice

The record of every decision, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

A **cluster** is a group of agents with one owner. A **task** is a saved instruction with a
timetable. From the specification:

- **A task carries a timetable, and the timetable decides everything about when it runs.**
  Saving a task without one is refused, and so is a timetable this system cannot work out
  how to follow.
- **A task never runs twice at once.** If a run is still going when the next one is due,
  the next one is skipped and says so.
- **A run left open for thirty minutes is treated as abandoned.** It is marked as
  interrupted and the next run is allowed to start.
- **Every run starts with an empty conversation.** Nothing an agent said last time is
  visible to it this time.
- **A task can name one agent, or leave it to the cluster's lead.** The lead may only hand
  work to agents in its own cluster, plus outside agents the cluster has chosen to follow.
- **A retired agent can never be given work**, whether it is named directly or chosen by
  the lead.
- **Every run records when it was meant to start and when it did**, so how late it ran is a
  number you can read.

---

## Design decisions

**Self-renewing reminders.** The platform can only set one reminder at a time, so each run
sets the next one before it begins. A timetable that repeats forever is built out of
reminders that each happen once.

**One record per agent.** Everything about an agent is kept in a single place that handles
one instruction at a time. Two things happening at once can never leave that agent's
conversations half-changed.

**The slot is the state.** A task holds its own "a run is happening" marker instead of
asking a shared database. The original needs a special database rule to stop two runs
overlapping; here the marker is part of the task, so there is nothing to keep in step.

**Refuse a timetable that cannot be followed.** A timetable is checked against both the
rules for writing one down and the rules for working out when it fires. The original
accepts four shapes it can then never follow, tells the caller they were saved, and
complains to a log file once a minute forever.

**A stand-in for the thinking part.** Each agent answers from a list written when it was
set up, instead of asking a language model. Every rule here is about what happens around
the answer rather than what the answer says, so the timings measure this system rather than
somebody else's.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/intentkit-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9016.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

No key for a model provider is needed. Nothing here calls one.

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9016**.

### Try it

```bash
# a cluster with an owner
curl -X PUT localhost:9016/clusters/acme \
  -H 'Content-Type: application/json' -d '{"ownerUserId":"ada"}'

# an agent in it, answering "done" the first time it is asked
curl -X PUT localhost:9016/clusters/acme/agents/reporter \
  -H 'Content-Type: application/json' \
  -d '{"slug":"reporter","name":"Reporter","visibility":"PRIVATE",
       "script":[{"failWith":null,"delayMillis":0,
                  "replies":[{"authorType":"AGENT","message":"done"}]}]}'

# a task that runs it every five minutes
curl -X POST localhost:9016/clusters/acme/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskId":"nightly","cron":"*/5 * * * *","prompt":"write the report",
       "enabled":true,"targetAgentId":"reporter"}'

# ask a timetable what it means, without saving anything
curl -X POST localhost:9016/schedules/explain \
  -H 'Content-Type: application/json' \
  -d '{"cron":"0 0 13 * 5","from":"2026-03-01T00:00:01Z","count":3}'

# what has run
curl localhost:9016/clusters/acme/tasks/nightly/executions
```

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9016` | Set in `src/main/resources/application.conf`. Nothing else is configurable; the limits below are fixed. |

| Limit | Value |
|---|---|
| Longest a run may stay open before it is treated as abandoned | 30 minutes |
| Runs kept per task | 50 |
| Conversations kept per agent | 50 |
| Messages kept per conversation | 100 |
| Failure notes kept per agent | 100 |
| How far one agent may pass work on | 5 hand-offs |
| Longest instruction | 20,000 characters |

---

## Where it differs from intentkit

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **Four timetables intentkit saves are refused here.** intentkit checks a timetable when
  you save it and again, with a different set of rules, when it works out the next run — and
  four shapes pass the first check and fail the second. intentkit saves them, tells you they
  were created, and writes a complaint to a log file every minute from then on; nothing runs.
  This port applies both sets of rules when you save, so a saved timetable is one that can
  be followed. The four are `0 0 */31 * *`, `0 0 * */12 *`, `5-3 0 * * *` and
  `0 0 * * sun-sat`.
- **"The shortest interval is five minutes" is not enforced by either.** intentkit refuses a
  timetable that runs more often than every five minutes only when the hour part is left
  open, so four shapes get past it and run every one, two or four minutes. This port accepts
  exactly the same set, and reports each timetable's true shortest gap alongside it, so a
  caller can see the real interval instead of trusting the refusal message.
- **A change takes effect straight away rather than within a minute.** intentkit has a
  background job that reads every task once a minute and brings the schedule into line with
  it. Here the schedule is set when the task is saved, and the checks that background job
  makes — the task is gone, switched off, or its cluster has no owner — are made when the
  run is due instead.
- **A run started by hand survives a restart.** intentkit starts it inside the web request,
  so it is lost if the process stops between accepting the request and starting the work.
  Here it is queued the same way a timetabled run is.
- **A task's state and its "a run is happening" marker cannot disagree.** intentkit sets the
  state from messages the scheduler sends after the fact, and its own note says the value
  written back reflects whatever was true when it was read. Here one instruction changes
  both, so you never see a task that says it is waiting while a run is under way.
- **Every run records what time it was meant to start.** intentkit records only when a run
  actually began, so how late it was is not a number that system holds. This port holds
  both, because a promise about lateness that nobody can check is worth less than the
  figure.
- **A run left open by a crash is corrected only when the task next runs.** Both systems
  wait for the next run to mark it as interrupted, so a task that never runs again keeps a
  record that says it is still going. This port kept that behaviour: correcting it would
  mean a new background pass over every task in the system to fix a field nobody is reading.
- **A failed run names a different kind of failure.** Both record the same sentence and the
  same message; the word for the kind of failure comes from the language each is written in,
  so one reads `RuntimeError` and the other `RuntimeException`.
- **An outside agent is found by its identifier, not by its short name.** intentkit looks a
  short name up across every cluster in the system. Here a short name only means something
  inside the cluster using it, and an agent in another cluster is named by its identifier —
  which is how it was followed in the first place.
- **The thinking part is a stand-in.** intentkit ends every run in a call to a language
  model; here an agent answers from a list written when it was set up. Nothing in the
  specification is about what the answer says, and a benchmark that called a model would be
  measuring the model.
- **Not checked: what happens across a region failure.** intentkit runs one scheduler
  process against one database; this port's schedule lives in the platform's own timer
  store. Neither was tested under a lost region, and the two are unlikely to behave the
  same way.
- **Not checked: what a caller sees while thousands of tasks fire at once.** Both systems
  were measured one task at a time.

---

## Licence

intentkit is MIT, © 2024 Crestal Network. This port reimplements the behaviour without
copied source; the refusal messages and identifier shapes it reproduces on purpose, and the
reasoning, are in [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
