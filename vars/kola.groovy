// Run kola tests on the latest build in the cosa dir
// Available parameters:
//    addExtTests:        []string -- list of test paths to run
//    allowUpgradeFail    boolean  -- warn instead of fail on upgrade failure
//    disableRerunSuccess boolean  -- disable rerun success (the default)
//    rerunSuccessArgs    string   -- override rerun success args (default: tags=needs-internet)
//    arch:               string   -- the target architecture
//    cosaDir:            string   -- cosa working directory
//    parallel:           integer  -- number of tests to run in parallel (default: # CPUs)
//    skipBasicScenarios  boolean  -- skip basic qemu scenarios
//    skipSecureBoot      boolean  -- skip secureboot tests
//    skipUpgrade:        boolean  -- skip running `cosa kola --upgrades`
//    build:              string   -- cosa build ID to target
//    platformArgs:       string   -- platform-specific kola args (e.g. '-p aws --aws-ami ...`)
//    extraArgs:          string   -- additional kola args for `kola run` (e.g. `ext.*`)
//    disableRerun:       boolean  -- disable reruns of failed tests
//    marker:             string   -- some identifying text to add to uploaded artifact filenames
def call(params = [:]) {
    def cosaDir = utils.getCosaDir(params)

    // this is shared between `kola run` and `kola run-upgrade`
    def platformArgs = params.get('platformArgs', "");
    def buildID = params.get('build', "latest");
    def arch = params.get('arch', "x86_64");
    def marker = params.get('marker', "");
    def rerun = ""
    if (!params.get('disableRerun', false)) {
        rerun = "--rerun"
        if (!params.get('disableRerunSuccess', false)) {
            // by default we'll allow rerun success for tests that need internet, but this can be overridden
            // by passing in a value to allowRerunSuccessArgs
            rerun += " --allow-rerun-success="
            rerun += params.get('rerunSuccessArgs', "tags=needs-internet")
        }
    }
    def archArg = "--arch=${arch}"

    // Define a unique token to be added to the file name uploads
    // Prevents multiple runs overwriting same filename in archiveArtifacts
    def token = shwrapCapture("uuidgen | cut -f1 -d-")

    // Create a unique output directory for this run of kola
    def outputDir = shwrapCapture("cd ${cosaDir} && cosa shell -- mktemp -d ${cosaDir}/tmp/kola-XXXXX")

    // list of identifiers for each run for log collection
    def ids = []

    // If given a marker then add it to the parallel run stage titles
    def titleMarker = marker == "" ? "" : "${marker}:"

    // A closure to help run kola. Common arguments and Error/Warning
    // handling are consolidated in this function as a convenience.
    def runKola = { id, action, args ->
        def rc = shwrapRc("""
            cd ${cosaDir}
            cosa kola ${action} ${rerun} --build=${buildID} --output-dir=${outputDir}/${id} \
                --on-warn-failure-exit-77 ${archArg} ${platformArgs} ${args}
        """)
        if (rc == 77) {
            warn("A warn:true test failed")
        } else if (rc != 0) {
            error("Script returned exit code ${rc}")
        }
    }

    // This is a bit obscure; what we're doing here is building a map of "name"
    // to "closure" which `parallel` will run in parallel. That way, we can
    // conditionally only add the `run_upgrades` stage if not explicitly
    // skipped.
    def kolaRuns = [:]
    kolaRuns["${titleMarker}kola"] = {
        def args = ""
        def id
        // Add the tests/kola directory, but only if it's not the same as the
        // src/config repo which is also automatically added.
        if (shwrapRc("""
            test -d ${env.WORKSPACE}/tests/kola
            configorigin=\$(cd ${cosaDir}/src/config && git config --get remote.origin.url)
            gitorigin=\$(cd ${env.WORKSPACE} && git config --get remote.origin.url)
            test "\$configorigin" != "\$gitorigin"
        """) == 0)
        {
            // The workspace name created by Jenkins is messy and dynamic, but
            // kola uses it to derive the test name. Let's fix it by using a
            // symlinked dir (x86_64) or copied dir (multi-arch).
            def name = shwrapCapture("basename \$(git config --get remote.origin.url) .git")
            if (arch == 'x86_64') {
                shwrap("mkdir -p /var/tmp/kola && ln -s ${env.WORKSPACE} /var/tmp/kola/${name}")
            } else {
                shwrap("""
                cd ${cosaDir} && cosa shell -- mkdir -p /var/tmp/kola
                cd ${cosaDir} && cosa remote-session sync ${env.WORKSPACE}/ :/var/tmp/kola/${name}/
                """)
            }
            args += "--exttest /var/tmp/kola/${name}"
        }
        def parallel = params.get('parallel', "auto");
        def extraArgs = params.get('extraArgs', "");
        def addExtTests = params.get('addExtTests', [])

        for (path in addExtTests) {
            args += " --exttest=${env.WORKSPACE}/${path}"
        }

        if (platformArgs != "" || extraArgs != "") {
            // There are two cases where we land here:
            //   1. The user passed `platformArgs`, which implies we're
            //      running cloud platform (non qemu) tests and don't need to
            //      worry about basic-qemu-scenarios or resource usage so we
            //      don't need to run reprovision tests separately.
            //   2. The user passed `extraArgs`. In that case they want more
            //      control over the kola run that might conflict with
            //      the --tag arguments we provide below. Let's just
            //      do a single run in that case.
            id = marker == "" ? "kola" : "kola-${marker}"
            ids += id
            runKola(id, 'run', "--parallel=${parallel} ${args} ${extraArgs}")
        } else {
            // basic run
            if (!params['skipBasicScenarios']) {
                id = marker == "" ? "kola-basic" : "kola-basic-${marker}"
                ids += id
                def skipSecureBootArg = ""
                if (params['skipSecureBoot']) {
                    skipSecureBootArg = "--skip-secure-boot"
                }
                runKola(id, 'run', "--basic-qemu-scenarios ${skipSecureBootArg}")
            }
            // normal run (without reprovision tests because those require a lot of memory)
            id = marker == "" ? "kola" : "kola-${marker}"
            ids += id
            runKola(id, 'run', "--tag='!reprovision' --parallel=${parallel} ${args}")

            // re-provision tests (not run with --parallel argument to kola)
            id = marker == "" ? "kola-reprovision" : "kola-reprovision-${marker}"
            ids += id
            runKola(id, 'run', "--tag='reprovision' ${args}")
        }
    }

    if (!params["skipUpgrade"]) {
        kolaRuns["${titleMarker}kola:upgrade"] = {
            // If upgrades are broken `cosa kola --upgrades` might
            // fail to even find the previous image so we wrap this
            // in a try/catch so allowUpgradeFail can work.
            def id = marker == "" ? "kola-upgrade" : "kola-upgrade-${marker}"
            ids += id
            try {
                runKola(id, 'run-upgrade',  "--upgrades")
            } catch(e) {
                // If we didn't even get logs then let's remove them from the list
                if (shwrapRc("cd ${cosaDir} && cosa shell -- test -d ${outputDir}/${id}") != 0) {
                    ids.remove(id)
                }
                if (params["allowUpgradeFail"]) {
                    warn(e.getMessage())
                } else {
                    throw e
                }
            }
        }
    }

    try {
        parallel(kolaRuns)
    } finally {
        for (id in ids) {
            // sanity check kola actually ran and dumped its output
            shwrap("cd ${cosaDir} && cosa shell -- test -d ${outputDir}/${id}")
            // collect the output
            shwrap("cd ${cosaDir} && cosa shell -- tar -C ${outputDir} -c --xz ${id} > ${env.WORKSPACE}/${id}-${token}.tar.xz || :")
            archiveArtifacts allowEmptyArchive: true, artifacts: "${id}-${token}.tar.xz"
        }
    }
}
