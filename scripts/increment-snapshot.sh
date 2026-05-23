#!/bin/bash
# Increment SNAPSHOT version
# Usage: ./scripts/increment-snapshot.sh [major|minor|patch]

set -e

BUMP_TYPE=${1:-patch}

CURRENT_VERSION=$(mvn help:evaluate -Dexpression=revision -q -DforceStdout)
echo "Current version: $CURRENT_VERSION"

# Remove -SNAPSHOT suffix for parsing
BASE_VERSION=${CURRENT_VERSION%-SNAPSHOT}
IFS='.' read -r MAJOR MINOR PATCH <<< "$BASE_VERSION"

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
    echo "Invalid bump type: $BUMP_TYPE"
    exit 1
    ;;
esac

echo "New version: $NEXT_VERSION"

sed -i.bak "s/<revision>.*<\/revision>/<revision>${NEXT_VERSION}<\/revision>/" pom.xml
rm -f pom.xml.bak

echo "Updated pom.xml to ${NEXT_VERSION}"
