#!/usr/bin/env bash
set -e

if [[ $(git status --porcelain) ]];
then
    echo "There are still changes present. Please commit those before updating version."
    exit 1
fi

defaultBranch="master"
currentBranch=$(git branch --show-current)
if [[ ${currentBranch} != "${defaultBranch}" ]];
then
    echo "Currently not on default branch. Please checkout ${defaultBranch} before retrying."
    exit 1
fi

if [[ $# != 1 ]];
then
    echo "Incorrect number of arguments provided. Usage: ./bump-versions.sh <major | minor | patch>"
    exit 1
fi

# validate the version argument
case "$1" in
    major|minor|patch|premajor|preminor|prepatch)
        ;;
    *)  echo "Invalid version bump argument provided. Usage ./bump-versions.sh <major | minor | patch>"
        exit 1
        ;;
esac

if [ -z "$(which semver)" ]
then
    echo "semver is not installed, install with 'brew install semver' or 'npm i -g semver'"
    exit 1
fi

currentVersion=$(grep version gradle.properties | sed 's/version=//')

if [ -z "$(semver "$currentVersion")" ];
then
  echo "Current version not found in gradle.properties"
  exit 1
fi

newVersion=$(semver "$currentVersion" --preid beta --increment "$1")
sed -i '' "s/$currentVersion/$newVersion/g" gradle.properties

echo "Bumping to $newVersion"

# commit the changes as a version commit
git add gradle.properties
git commit -m "v${newVersion}"

# git tag the version change commit
LATEST_GIT_SHA=$(git rev-parse HEAD)
git tag -a "v${newVersion}" "${LATEST_GIT_SHA}" -m "v${newVersion}"
