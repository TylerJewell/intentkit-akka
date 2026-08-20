# Acknowledgements

This project is a port of **[crestalnetwork/intentkit](https://github.com/crestalnetwork/intentkit)**,
read and run at commit `d4267e9` (2026-08-01).

## Licence

intentkit is **MIT**, © 2024 Crestal Network. A copy of that licence is included as
`LICENSE-intentkit`, which MIT requires of any work that carries its material.

## What was copied

**No source was copied.** No Python file, fragment or expression from intentkit
appears here; every file in `src/` was written for this project.

Four kinds of thing were taken across verbatim, and all four are strings a caller
reads, not code:

- **Refusal keys** — `InvalidAutonomousConfig`, `InvalidCronExpression`,
  `InvalidAutonomousInterval`, and the messages that go with them.
- **Run outcome text** — `Task execution error: …`, `Unexpected return error`,
  `Unexpected result: empty response`, `Autonomous task exception: …`, `interrupted`.
- **Delegation refusals** — `Agent '…' is archived`, `Agent '…' not found`,
  `Agent '…' is not accessible to this team. Use lead_follow_agent to follow it first.`,
  `Maximum call_agent recursion depth (5) exceeded`.
- **Identifier shapes** — the job id `{teamId}-{taskId}`, the chat id
  `autonomous-{taskId}`, and the synthetic lead agent id `team-{teamId}`.

They are reproduced deliberately: a port whose refusals read differently is a port a
caller cannot swap in, and the benchmark compares them character for character.

## What is derived

The behaviour is. Every rule in `intentkit-port/specs/SPEC-001-intentkit.md` was
established by reading and then running intentkit — its schedule validator, the
APScheduler trigger it builds, its run slot against a real PostgreSQL, its runner and
its delegation tool. The record of what was checked and how is
`intentkit-port/docs/question-log.md`.

## Also used

- **Akka** (Akka SDK for Java, BSL 1.1) — the platform this port is built on.
- **APScheduler** (MIT) and **cron-validator** (MIT) were run, not copied, to
  establish what intentkit's schedules mean. This project's cron engine
  (`CronSchedule`, `CronPolicy`) was written from the answers those two produced, not
  from their source.
