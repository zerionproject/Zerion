#!/usr/bin/env bash
#
# Builds the app the way F-Droid's build server does: `fdroid build --test
# --on-server` in registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie,
# with fdroidserver master and the metadata written by fdroid-metadata.py on
# top of fdroiddata master. Tor and Monero are compiled from pinned source by
# the recipe and every Gradle release gate runs (dependency verification,
# native pins, enforceNoLogs, verifyTranslations, R8, the wallet regression
# suite). The unsigned APK and the build log land in /out.
#
# Runs inside the container:
#   docker run --rm -e VERCODE=<versionCode> -v <dir with metadata.yml and
#     zerion.git>:/in:ro -v <out dir>:/out <image> bash /in/fdroid-reference-build.sh
#
set -euo pipefail
: "${VERCODE:?}"
APPID=com.professor.zerion
git config --system --add safe.directory '*'
source /etc/profile.d/bsenv.sh
export CI_PROJECT_DIR=/builds/fdroid/fdroiddata
export DEBIAN_FRONTEND=noninteractive
mkdir -p /builds/fdroid
git clone -q --depth 1 https://gitlab.com/fdroid/fdroiddata.git "$CI_PROJECT_DIR"
cp /in/metadata.yml "$CI_PROJECT_DIR/metadata/$APPID.yml"
cd "$CI_PROJECT_DIR"
echo "fdroiddata $(git log -1 --format=%H)"
test -d build || mkdir build
apt-get update >/dev/null && apt-get -qy dist-upgrade >/dev/null
yes | sdkmanager "platform-tools" "build-tools;31.0.0" >/dev/null 2>&1 || true
rm -rf "$fdroidserver"; mkdir "$fdroidserver"
curl --silent --fail https://gitlab.com/fdroid/fdroidserver/-/archive/master/fdroidserver-master.tar.gz \
	| tar -xz --directory="$fdroidserver" --strip-components=1
export PATH="$fdroidserver:$PATH"
export PYTHONPATH="$fdroidserver:$fdroidserver/examples"
export PYTHONUNBUFFERED=true
fdroid readmeta
fdroid lint "$APPID" > /tmp/lint.log 2>&1 || true
if grep -v -E "unsafe permissions on 'config.yml'|Repo: git URLs must use https://" /tmp/lint.log \
		| grep -q -E "^$APPID:|ERROR|WARNING"; then
	cat /tmp/lint.log
	echo "fdroid lint reported a problem with the metadata"
	exit 1
fi
git -C "$home_vagrant/gradlew-fdroid" pull -q
for d in logs tmp unsigned "$home_vagrant/.android" "$home_vagrant/.gradle" "$home_vagrant/metadata"; do
	test -d "$d" || mkdir "$d"
	chown -R vagrant "$d"
done
ln -s "$home_vagrant/.gradle" "$CI_PROJECT_DIR/.gradle"
ln -s "$CI_PROJECT_DIR/tmp" "$home_vagrant/tmp"
ln -s "$CI_PROJECT_DIR/srclibs" "$home_vagrant/srclibs"
export GRADLE_USER_HOME="$home_vagrant/.gradle"
fdroid="sudo --preserve-env --user vagrant env PATH=$fdroidserver:$PATH env PYTHONPATH=$fdroidserver:$fdroidserver/examples env PYTHONUNBUFFERED=true env TERM=xterm env HOME=$home_vagrant fdroid"
apt-get install -y sudo openjdk-21-jdk-headless >/dev/null
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
cp -R "$CI_PROJECT_DIR/build" "$home_vagrant/build"
cp -R "metadata/$APPID.yml" "$home_vagrant/metadata"
chown -R vagrant "$home_vagrant" "$CI_PROJECT_DIR"
cd "$home_vagrant"
ln -s "$CI_PROJECT_DIR" "$home_vagrant/fdroiddata"
$fdroid fetchsrclibs "$APPID:$VERCODE"
rm "$home_vagrant/fdroiddata"
set +e
(unset CI; $fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$APPID:$VERCODE") > /tmp/fdroid-build.log 2>&1
rc=$?
set -e
cp /tmp/fdroid-build.log /out/fdroid-build.log
if [ $rc -ne 0 ]; then
	tail -n 200 /tmp/fdroid-build.log
	echo "fdroid build failed"
	exit 1
fi
APK="$CI_PROJECT_DIR/tmp/${APPID}_${VERCODE}.apk"
[ -f "$APK" ] || APK="$CI_PROJECT_DIR/unsigned/${APPID}_${VERCODE}.apk"
cp "$APK" "/out/${APPID}_${VERCODE}-unsigned.apk"
sha256sum "/out/${APPID}_${VERCODE}-unsigned.apk"
