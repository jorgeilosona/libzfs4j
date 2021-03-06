#! /usr/bin/env bash

# Try to "lockpick" the settings relevant for libzfs.jar on this host OS
# Note that the "correct answer" may change across OS updates... as well
# as libzfs.jar evolution. Requires sources, "mvn" and JDK at this time,
# and you may have to allow tests in pom.xml (set "<skip>" to "false").
# Note it is likely to leave around Java coredump files, or at least logs
# of those with stack traces.
#
# Recommended usage to track down issues:
#  VERBOSITY=high ./lockpick-libzfs-abi.sh | tee "lock.`date +%s`.log"
#
# See also some docs:
#  http://maven.apache.org/surefire/maven-surefire-plugin/examples/single-test.html
#
# Copyright (C) 2017 by Jim Klimov, on the terms of CDDL license:
#
# This file and its contents are supplied under the terms of the
# Common Development and Distribution License ("CDDL"). You may
# only use this file in accordance with the terms of the CDDL.
#
# A full copy of the text of the CDDL should have accompanied this
# source. A copy of the CDDL is also available via the Internet at
# http://www.illumos.org/license/CDDL.
#

# Bashism to allow pipes to fail not only due to last called program
if [ -n "${BASH_VERSION-}" ]; then
    set -o pipefail
fi

# Abort on unhandled errors
set -e

# We do not care for these coredumps
# But the hs_err_pid*.log files are not so easy to avoid
ulimit -c 0 || true

[ -n "${VERBOSITY-}" ] || VERBOSITY=quiet

# Put the variants with longer argument lists or otherwise more-probable
# to fail first (newer ABIs usually), to reduce amount of false-positive
# identifications. In many cases, the legacy set of arguments fits inside
# newer argument list and so does not cause a linking error, but still can
# potentially pass random heap garbage to the actually called new function.
# Find the options scaterred in sources (mostly ZFSObject.java).

# Alas, associative arrays of bash4 are not yet available in Solaris 10
# (at least as "recent" as 10u8) which is a platform of interest for this.
LIBZFS_VARIANT_FUNCTIONS__zfs_snapshot="openzfs legacy pre-nv96"
LIBZFS_VARIANT_FUNCTIONS__zfs_iter_snapshots="openzfs legacy"
LIBZFS_VARIANT_FUNCTIONS__zfs_destroy_snaps="openzfs legacy"
LIBZFS_VARIANT_FUNCTIONS__zfs_destroy="openzfs legacy"

# TODO: New ABI syntax for either major branch of ZFS has not yet been
# figured out, so routines are sort of deprecated (NO-OPs) until then.
# Still, to allow yet older Solarises to run well, you can want to test
# the old ABI first -- be sure to use TEST_OK_ZERO_ONLY=yes as well.
# By currently more probable default we just "no-op" them, although
# the "legacy" or "openzfs" options are also valid until implemented.
#LIBZFS_VARIANT_FUNCTIONS__zfs_perm_remove="pre-sol10u8 NO-OP"
#LIBZFS_VARIANT_FUNCTIONS__zfs_perm_set="pre-sol10u8 NO-OP"
LIBZFS_VARIANT_FUNCTIONS__zfs_perm_remove="NO-OP pre-sol10u8"
LIBZFS_VARIANT_FUNCTIONS__zfs_perm_set="NO-OP pre-sol10u8"

# List of functions in the pattern above
LIBZFS_VARIANT_FUNCTIONS="$(echo ${!LIBZFS_VARIANT_FUNCTIONS__*} | sed 's,LIBZFS_VARIANT_FUNCTIONS__,,g')"

### NOTE: Better just create that dataset and `zfs allow` your account to test
### in it - avoids NPEs in some tests that preclude actual routines.
### Can do this (as root):
###   mkfile -v 16M testpool.img && zpool create testpool `pwd`/testpool.img
### to create a test pool and set ZFS management permissisons on test dataset:
###   sudo zfs create testpool/testdataset
###   sudo zfs allow -ld "$USER" mount,create,share,destroy,snapshot testpool/testdataset
LIBZFSTEST_DATASET="rpool/kohsuke"
#LIBZFSTEST_DATASET="testrpool/testdataset"
LIBZFSTEST_MVN_OPTIONS="${LIBZFSTEST_MVN_OPTIONS-} -Dlibzfs.test.pool=${LIBZFSTEST_DATASET}"
LIBZFSTEST_MVN_OPTIONS="${LIBZFSTEST_MVN_OPTIONS-} -Dlibzfs.test.loglevel=FINEST"
### Avoid parallel test-cases inside our class - it is unreadable to debug
LIBZFSTEST_MVN_OPTIONS="${LIBZFSTEST_MVN_OPTIONS-} -Dparallel=classes -DforkCount=0"


die() {
    RES="$1"
    [ -n "$RES" ] && [ "$RES" -gt 0 ] && shift || RES=1
    if [ -n "$*" ]; then
        echo "FATAL: $@" >&2
    fi
    exit $RES
}

report_match() {
    SETTINGS="$(set | egrep '^LIBZFS4J_.*=')" || true
    uname -a
    echo ""
    echo "MATCHED with the following settings: " $SETTINGS
    return 0
}

build_libzfs() {
    echo "Building latest libzfs4j.jar and tests..."
    mvn compile test-compile 2>/dev/null >/dev/null && echo "OK" || \
        die $? "FAILED to build code"
}

test_libzfs() (
    echo ""
    echo "Testing with the following settings:"
    SETTINGS="$(set | egrep '^LIBZFS4J_.*=')" || true
    echo "$SETTINGS"

    RES=0
    DUMPING_OPTS="-XX:ErrorFile=/dev/null -Xmx64M"
    MAVEN_OPTS="${DUMPING_OPTS}"
    export MAVEN_OPTS
    OUT="$(mvn -DargLine="${DUMPING_OPTS}" $LIBZFSTEST_MVN_OPTIONS "$@" test 2>&1)" || RES=$?
    case "$VERBOSITY" in
    high)
        echo "$OUT" | egrep '^FINE.*libzfs4j features.*LIBZFS4J' | uniq || true
        echo "$OUT" | egrep -v '^FINE.*libzfs4j features.*LIBZFS4J|org.jvnet.solaris.libzfs.LibZFS initFeatures|^$' || true
        ;;
    yes)
        echo "$OUT" | egrep '^FINE.*LIBZFS4J' | uniq || true
        echo "$OUT" | egrep 'testfunc_' | uniq || true
        ;;
    quiet|*) ;;
    esac

    case "$RES" in
        0|1) # Test could fail e.g. due to inaccessible datasets
             # or permissions. Normally we do not bail on this.
            echo "SUCCESS (did not core-dump, returned $RES)"
            if [ "$TEST_OK_ZERO_ONLY" = yes ]; then
                return $RES
            fi
            return 0
            ;;
        134|*) # 134 = 128 + 6 = coredump on OS signal SEGABRT
            echo "FAILED ($RES) with the settings above:" $SETTINGS >&2
            ;;
    esac
    return $RES
)

test_linkability() {
    echo "Test linkability of Native ZFS from Java..."
    TEST_OK_ZERO_ONLY=yes test_libzfs -Dlibzfs.test.funcname=testCouldStart -X >/dev/null 2>&1 \
        && echo SUCCESS || die $? "Does this host have libzfs.so?"
}

test_defaults() {
    echo "Try with default settings and an auto-guesser..."
    RES_LOOP=127
    for LIBZFS4J_ABI in \
        legacy \
        openzfs \
        "" \
    ; do
        test_libzfs && return 0 || RES_LOOP=$?
    done
    return $RES_LOOP
}

test_all_routines() {
    echo "Re-validate specific routines"
    test_libzfs -Dlibzfs.test.funcname="${LIBZFS_VARIANT_FUNCTIONS}" || return $?
}


test_lockpick() {
    # Override the default for individual variants explicitly in the loop below
    LIBZFS4J_ABI=""

    echo ""
    echo "Simple approach failed - begin lockpicking..."
    for ZFS_FUNCNAME in ${LIBZFS_VARIANT_FUNCTIONS} ; do
        eval LIBZFS4J_ABI_${ZFS_FUNCNAME}="NO-OP"
        eval export LIBZFS4J_ABI_${ZFS_FUNCNAME}
    done

    for ZFS_FUNCNAME in ${LIBZFS_VARIANT_FUNCTIONS} ; do
        echo ""
        # Note: Empty token must be in the end - for library picking defaults,
        # and as the fatal end of loop if nothing tried works for this system.
        for ZFS_VARIANT in `eval echo '$'"{LIBZFS_VARIANT_FUNCTIONS__${ZFS_FUNCNAME}}"` "" ; do
            eval LIBZFS4J_ABI_${ZFS_FUNCNAME}="${ZFS_VARIANT}"
            echo "Testing function variant LIBZFS4J_ABI_${ZFS_FUNCNAME}='${ZFS_VARIANT}'..."
            if test_libzfs -Dlibzfs.test.funcname="${ZFS_FUNCNAME}" -X ; then
                break
            fi
            if [ -z "$ZFS_VARIANT" ]; then
                die 1 "FAILED to find a working variant for $ZFS_FUNCNAME"
            fi
            #die 1 "After first test"
        done
        echo "============== Picked function variant LIBZFS4J_ABI_${ZFS_FUNCNAME}='${ZFS_VARIANT}'..."
        #die 1 "After one loop"
    done

    # if here, we found variants for all functions
    return 0
}

##################### DO THE WORK #########################

build_libzfs

# Value set in looped calls below
export LIBZFS4J_ABI

test_linkability

### Quick tests may give false comfort
if [ "$FORFEIT_LOCKPICK" = yes ] ; then
    test_defaults && test_all_routines && report_match && exit || \
    echo "Deeper tests are needed..."
fi

test_lockpick

echo ""
echo "Re-validating the full set of lockpicking results..."
VERBOSITY=high test_libzfs && VERBOSITY=high test_all_routines || die $? "FAILED re-validation"

echo ""
echo "Packaging the results..."
mvn $LIBZFSTEST_MVN_OPTIONS package || echo "FAILED packaging (maybe tests failed due to missing datasets/permissions - then fix pom.xml back to skipt tests and re-run "mvn package")"

report_match
exit 0
