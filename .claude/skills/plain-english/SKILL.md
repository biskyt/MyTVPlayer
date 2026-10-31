---
name: plain-english
description: Write in ASD-STE100 Simplified Technical English. Use when writing or editing any documentation, including docs/, context/, plans/, ADRs, READMEs, CLAUDE.md or AGENTS.md files, code comments that explain behaviour, commit messages and PR descriptions.
---

# Plain English for documentation

Write in ASD-STE100 Simplified Technical English. This is the controlled language that aerospace manuals have used since 1983. A tired developer at 3 a.m. must understand the text on the first read. So must a manager who never wrote code.

## Scope

- Apply these rules to all new text, and to each sentence that you change.
- Do not rewrite text that you were not asked to change. Old documents comply only when someone edits them.
- These rules control the language. The document conventions of the repository still apply, for example the rule against hard-wrapped paragraphs.

## Rules

1. One sentence carries one instruction or one fact. Maximum 20 words.
2. One word has one meaning everywhere in the document. If "release" means "deploy" in one section, it never means "let go of a lock" in another.
3. Use the active voice and simple tenses. Write "The server rejects the request", not "the request would be getting rejected".
4. Use "can", "will" and "must" for ability, future and duty. Write "possibly" or "we recommend" when something is optional.
5. Put the condition before the instruction. Write "If the test fails, read the log", not "Read the log if the test fails".
6. Keep the articles ("a", "the") and the word "that". The goal is clear text, not short text.
7. When a technical term must appear, define it in plain words the first time that it appears.
8. If the document must be complete, keep every decision, number, threshold, condition and risk. Add sentences, not sentence length.
9. If you write a thing for someone, write only that thing. A commit message has no introduction and no closing offer.

## Do not change

- Code, commands, error messages, file paths, identifiers and numbers stay exactly as they are.
- Keep the scope of each statement. Do not change "only after a restart" into "always". Do not round a number that a reader needs to act.
- Keep the reasons and the risks at full strength, one clear sentence at a time. Remove ceremony, not reasoning.
- Security warnings, irreversible actions and ordered steps need these rules most. Use numbered lists for steps where the order matters.

## Example

Before:

> Should the migration be found to have been run against a database that hasn't had the prerequisite seed data applied, it would be advisable to roll it back prior to attempting any further steps.

After:

> The migration needs the seed data. If you ran the migration before the seed data, roll it back. Then load the seed data and run the migration again.

## Check before you finish

- Is any sentence longer than 20 words? Split it.
- Does any word have two meanings in the document? Choose one meaning, and use a different word for the other.
