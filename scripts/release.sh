#!/bin/bash
# Release script for wmt-agent-core
# Usage: ./scripts/release.sh [major|minor|patch]
#
# This script:
# 1. Merges main to release branch
# 2. Bumps version (removes -SNAPSHOT)
# 3. Tags and pushes
# 4. Merges back to main
# 5. Increments to next SNAPSHOT

set -e

BUMP_TYPE=${1:-patch}

echo "=== Starting release process (bump: $BUMP_TYPE) ==="

# Ensure we're on main and up to date
git checkout main
git pull origin main

# Get current version
CURRENT_VERSION=$(mvn help:evaluate -Dexpression=revision -q -DforceStdout)
echo "Current version: $CURRENT_VERSION"

# Remove -SNAPSHOT for release
RELEASE_VERSION=${CURRENT_VERSION%-SNAPSHOT}
echo "Release version: $RELEASE_VERSION"

# Parse version components
IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"

# Calculate next version based on bump type
case $BUMP_TYPE in
  major)
    NEXT_MAJOR=$((MAJOR + 1))
    NEXT_VERSION="${NEXT_MAJOR}.0.0-SNAPSHOT"
    ;;
  minor)
    NEXT_MINOR=$((MINOR + 1))
    NEXT_VERSION="${MAJOR}.${NEXT_MINOR}.0-SNAPSHOT"
    ;;
  patch)
    NEXT_PATCH=$((PATCH + 1))
    NEXT_VERSION="${MAJOR}.${MINOR}.${NEXT_PATCH}-SNAPSHOT"
    ;;
  *)
    echo "Invalid bump type: $BUMP_TYPE (use major|minor|patch)"
    exit 1
    ;;
esac

echo "Next SNAPSHOT version: $NEXT_VERSION"

# Create release tag
echo "=== Creating release tag v${RELEASE_VERSION} ==="
git tag -a "v${RELEASE_VERSION}" -m "Release ${RELEASE_VERSION}"
git push origin "v${RELEASE_VERSION}"

# Update to next SNAPSHOT version
echo "=== Updating to next SNAPSHOT version ==="
sed -i.bak "s/<revision>.*<\/revision>/<revision>${NEXT_VERSION}<\/revision>/" pom.xml
rm -f pom.xml.bak

git add pom.xml
git commit -m "chore: Bump version to ${NEXT_VERSION} [skip ci]"
git push origin main

echo "=== Release complete ==="
echo "Released: v${RELEASE_VERSION}"
echo "Next dev version: ${NEXT_VERSION}"
echo ""
echo "Looper will now deploy ${RELEASE_VERSION} to Nexus from the tag."
