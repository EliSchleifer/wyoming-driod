# Push wyoming-driod from your own machine

This bundle contains the full history of branch `claude/bold-edison-t3Yg0`
(3 signed commits: the Wyoming satellite app, the GitHub Actions build, and
the mDNS TXT-records change) on top of the repo's initial commit.

## Option 1 — fresh clone from the bundle (simplest)

    git clone wyoming-driod.bundle wyoming-driod
    cd wyoming-driod
    git remote set-url origin https://github.com/EliSchleifer/wyoming-driod.git
    git push origin claude/bold-edison-t3Yg0

## Option 2 — into an existing clone of the repo

    cd /path/to/your/wyoming-driod
    git fetch /path/to/wyoming-driod.bundle claude/bold-edison-t3Yg0:claude/bold-edison-t3Yg0
    git push origin claude/bold-edison-t3Yg0

After pushing, GitHub Actions (.github/workflows/build.yml) builds the debug
APK and uploads it as the "wyoming-satellite-debug" artifact under the
Actions tab. Open a PR from the branch when ready.
