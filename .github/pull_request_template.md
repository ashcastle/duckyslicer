## Outcome

Describe the user-visible or maintainer-visible result. Link the related issue when one exists.

## Implementation

Summarize the important design choice and any compatibility or migration impact.

## Validation

List the exact checks that passed. Explain any relevant check that could not run.

## User-visible evidence

Include before-and-after screenshots for UI or preview changes. Write `Not applicable` otherwise.

## Risk and recovery

Describe important remaining risk and how this change can be reverted or recovered.

## Checklist

Check each applicable item and explain any exception above.

- [ ] The change is focused and does not overwrite unrelated work.
- [ ] Tests cover the changed behavior, including failure and recovery where relevant.
- [ ] User-facing text is plain English with a complete Korean resource; inherited translations are generated, not hand-edited.
- [ ] Offline slicing remains usable without an account, cloud service, or network connection.
- [ ] Native or packaging changes retain ARM64 16 KB compatibility.
- [ ] No credentials, private models, private G-code, personal data, local AI instructions, APKs, or temporary build files are included.
- [ ] New third-party code or assets have reviewed license and attribution information.
- [ ] Privacy, security, support, and store disclosures were updated when behavior or data handling changed.
- [ ] The appropriate local gate passed, or the limitation and substitute evidence are stated above.
