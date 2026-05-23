#!/bin/bash
# Release script for Agent Core
# Usage: ./scripts/release.sh [major|minor|patch]
#
# Flow:
#   1. Ensure main is up to date
#   2. Strip -SNAPSHOT  →  commit "prepare release X.Y.Z [skip ci]"
#   3. Tag  vX.Y.Z  at that commit
#   4. Bump to next SNAPSHOT  →  commit "prepare next development iteration [skip ci]"
#   5. Push commits + tag
#   6. GitHub Actions release.yml picks up the tag and publishes the artifact + GitHub Release

set -euo pipefail

BUMP_TYPE=${1:-patch}

echo "=== Agent Core Release (bump: $BUMP_TYPE) ==="

# ── 1. Ensure we're on main and up to date ─────────────────────────────────
git checkout main
git pull origin main

# ── 2. Read current version ────────────────────────────────────────────────
CURRENT_VERSION=$(mvn help:evaluate -Dexpression=revision -q -DforceStdout)
echo "Current version : $CURRENT_VERSION"

if [[ "$CURRENT_VERSION" != *"-SNAPSHOT" ]]; then
  echo "ERROR: Expected a -SNAPSHOT version in pom.xml, got '$CURRENT_VERSION'. Aborting."
  exit 1
fi

# Strip -SNAPSHOT
RELEASE_VERSION=${CURRENT_VERSION%-SNAPSHOT}
echo "Release version : $RELEASE_VERSION"

# ── 3. Calculate next SNAPSHOT ─────────────────────────────────────────────
IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"

case $BUMP_TYPE in
  major)
    NEXT_VERSION="$((MAJOR + 1)).0.0-SNAPSHOT"
    ;;
  minor)
    NEXT_VERSION="${MAJOR}.$((MINOR + 1)).0-SNAPSHOT"
    ;;
  patch)
    NEXT_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"
    ;;
  *)
    echo "ERROR: Invalid bump type '$BUMP_TYPE'. Use: major | minor | patch"
    exit 1
    ;;
esac

echo "Next SNAPSHOT   : $NEXT_VERSION"
echo ""

# ── 4. Set pom.xml to release version and commit ──────────────────────────
echo "=== Preparing release commit ==="
sed -i.bak "s|<revision>.*</revision>|<revision>${RELEASE_VERSION}</revision>|" pom.xml
rm -f pom.xml.bak

git add pom.xml
git commit -m "chore: prepare release ${RELEASE_VERSION} [skip ci]"

# ── 5. Tag the release commit ─────────────────────────────────────────────
echo "=== Tagging v${RELEASE_VERSION} ==="
git tag -a "v${RELEASE_VERSION}" -m "Release ${RELEASE_VERSION}"

# ── 6. Bump to next SNAPSHOT and commit ───────────────────────────────────
echo "=== Bumping to next development iteration ==="
sed -i.bak "s|<revision>.*</revision>|<revision>${NEXT_VERSION}</revision>|" pom.xml
rm -f pom.xml.bak

git add pom.xml
git commit -m "chore: prepare next development iteration ${NEXT_VERSION} [skip ci]"

# ── 7. Push commits and tag ───────────────────────────────────────────────
echo "=== Pushing to origin ==="
git push origin main
git push origin "v${RELEASE_VERSION}"

echo ""
echo "=== Done ==="
echo "  Released  : v${RELEASE_VERSION}"
echo "  Next dev  : ${NEXT_VERSION}"
echo ""
echo "GitHub Actions release.yml will now:"
echo "  • Build and verify the tagged commit"
echo "  • Publish io.agentcore:agentcore:${RELEASE_VERSION} to GitHub Packages"
echo "  • Create the GitHub Release at v${RELEASE_VERSION}"
