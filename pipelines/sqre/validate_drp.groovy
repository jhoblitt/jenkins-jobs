node('jenkins-master') {
  if (params.WIPEOUT) {
    deleteDir()
  }

  dir('jenkins-dm-jobs') {
    checkout([
      $class: 'GitSCM',
      branches: scm.getBranches(),
      userRemoteConfigs: scm.getUserRemoteConfigs(),
      changelog: false,
      poll: false
    ])
    notify = load 'pipelines/lib/notify.groovy'
    util = load 'pipelines/lib/util.groovy'
    scipipe = util.scipipeConfig()
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'COMPILER',
    'EUPS_TAG',
    'MANIFEST_ID',
    'NO_PUSH',
    'WIPEOUT',
  ])

  String manifestId = params.MANIFEST_ID
  String compiler   = params.COMPILER
  String eupsTag    = params.EUPS_TAG
  Boolean noPush    = params.NO_PUSH
  Boolean wipeout   = params.WIPEOUT

  // optional
  String relImage = params.RELEASE_IMAGE

  def dockerRepo = scipipe.scipipe_release.docker_registry.repo
  relImage = relImage ?: "${dockerRepo}:7-stack-lsst_distrib-${eupsTag}"

  target = [
    manifestId: manifestId,
    compiler: compiler,
    eupsTag: eupsTag,
    noPush: noPush,
    wipeout: wipeout,
  ]

  def masterRetries = 3

  def matrix = [
    cfht: {
      drp('cfht', target, 'master', true, masterRetries, 1, [
        relImage: relImage,
      ])
    },
    /*
    hsc: {
      drp('hsc', target, 'master', true, masterRetries, 15, [
        relImage: relImage,
      ])
    },
    */
  ]

  stage('matrix') {
    parallel(matrix)
  }
} // notify.wrap

/**
 * Prepare, execute, and record results of a validation_drp run.
 *
 * @param datasetSlug String short name of dataset
 * @param target Map docker image selection + build configuration
 * @param drpRef String validate_drp git repo ref. Defaults to 'master'
 * @param doDispatchqa Boolean Enables/disables running of displatch-verify. Defaults to true.
 * @param retries Integer Number of times to retry after a failure
 * @param timelimit Integer Maximum number of hours per 'try'
 * 'true'
 */
def void drp(
  String datasetSlug,
  Map target,
  String drpRef = 'master',
  Boolean doDispatchqa = true,
  Integer retries = 3,
  Integer timelimit = 12,
  Map p
) {
  util.requireMapKeys(p, [
    'relImage',
  ])

  def datasetInfo  = datasetLookup(datasetSlug)
  def drpRepo      = util.githubSlugToUrl('lsst/validate_drp')
  def jenkinsDebug = 'true'

  def run = { runSlug ->
    def baseDir           = "${pwd()}/${runSlug}"
    def drpDir            = "${baseDir}/validate_drp"
    def datasetDir        = "${baseDir}/${datasetInfo['dataset']}"
    def homeDir           = "${baseDir}/home"
    def runDir            = "${baseDir}/run"
    def archiveDir        = "${baseDir}/archive"
    def datasetArchiveDir = "${archiveDir}/${datasetInfo['dataset']}"
    def fakeLsstswDir     = "${baseDir}/lsstsw-fake"
    def fakeManifestFile  = "${fakeLsstswDir}/build/manifest.txt"
    def fakeReposFile     = "${fakeLsstswDir}/etc/repos.yaml"
    def ciDir             = "${baseDir}/ci-scripts"

    try {
      dir(baseDir) {
        // empty ephemeral dirs at start of build
        util.emptyDirs([
          archiveDir,
          datasetArchiveDir,
          "${fakeLsstswDir}/build",
          "${fakeLsstswDir}/etc",
          homeDir,
          runDir,
        ])

        // stage manifest.txt early so we don't risk a long processing run and
        // then fail setting up to run post-qa
        // testing
        downloadManifest(fakeManifestFile, target.manifestId)
        downloadRepos(fakeReposFile)

        dir(ciDir) {
          util.cloneCiScripts()
        }

        // clone validation dataset
        dir(datasetDir) {
          timeout(time: datasetInfo['cloneTime'], unit: 'MINUTES') {
            util.checkoutLFS(
              githubSlug: datasetInfo['datasetGithubRepo'],
              gitRef: datasetInfo['datasetRef'],
            )
          }
        }

        util.insideDockerWrap(
          image: p.relImage,
          pull: true,
        ) {
          // clone and build validate_drp from source
          dir(drpDir) {
            // the simplier git step doesn't support 'CleanBeforeCheckout'
            timeout(time: 15, unit: 'MINUTES') {
              checkout(
                scm: [
                  $class: 'GitSCM',
                  branches: [[name: "*/${drpRef}"]],
                  doGenerateSubmoduleConfigurations: false,
                  extensions: [[$class: 'CleanBeforeCheckout']],
                  submoduleCfg: [],
                  userRemoteConfigs: [[url: drpRepo]]
                ],
                changelog: false,
                poll: false,
              )
            } // timeout

            // XXX DM-12663 validate_drp must be built from source / be
            // writable by the jenkins role user -- the version installed in
            // the container image can not be used.
            buildDrp(
              homeDir,
              drpDir,
              runSlug,
              ciDir,
              target.compiler
            )
          } // dir

          timeout(time: datasetInfo['runTime'], unit: 'MINUTES') {
            runDrp(
              drpDir,
              runDir,
              datasetInfo['dataset'],
              datasetDir,
              datasetArchiveDir
            )
          } // timeout
          // push results to squash, verify version
          if (doDispatchqa) {
            runDispatchqa(
              runDir,
              drpDir,
              datasetArchiveDir,
              fakeLsstswDir,
              datasetInfo['dataset'],
              target.noPush
            )
          }
        } // inside
      } // dir
    } finally {
      // collect artifacats
      // note that this should be run relative to the origin workspace path so
      // that artifacts from parallel branches do not collide.
      record(archiveDir, drpDir, fakeLsstswDir)
    }
  } // run

  // retrying is important as there is a good chance that the dataset will
  // fill the disk up
  retry(retries) {
    try {
      node('docker') {
        timeout(time: timelimit, unit: 'HOURS') {
          if (target.wipeout) {
            deleteDir()
          }

          // create a unique sub-workspace for each parallel branch
          def runSlug = datasetSlug
          if (drpRef != 'master') {
            runSlug += "-" + drpRef.tr('.', '_')
          }

          run(runSlug)
        } // timeout
      } // node
    } catch(e) {
      runNodeCleanup()
      throw e
    }
  } // retry
} // drp

/**
 * Trigger jenkins-node-cleanup (disk space) and wait for it to complete.
 */
def void runNodeCleanup() {
  build job: 'sqre/infra/jenkins-node-cleanup',
    wait: true
}

/**
 * XXX this type of configuration data probably should be in an external config
 * file rather than mixed with code.
 *
 *  Lookup dataset details ("full" name / repo / ref)
 *
 * @param datasetSlug String short name of dataset
 * @return Map of dataset specific details
 */
def Map datasetLookup(String datasetSlug) {
  def info = [:]
  info['datasetSlug'] = datasetSlug

  // all of this information could presnetly be computed heuristically -- but
  // perhaps this won't be the case in the future?
  switch(datasetSlug) {
    case 'cfht':
      info['dataset']           = 'validation_data_cfht'
      info['datasetGithubRepo'] = 'lsst/validation_data_cfht'
      info['datasetRef']        = 'master'
      info['cloneTime']         = 15
      info['runTime']           = 15
      break
    case 'hsc':
      info['dataset']           = 'validation_data_hsc'
      info['datasetGithubRepo'] = 'lsst/validation_data_hsc'
      info['datasetRef']        = 'master'
      info['cloneTime']         = 240
      info['runTime']           = 600
      break
    case 'decam':
      info['dataset']           = 'validation_data_decam'
      info['datasetGithubRepo'] = 'lsst/validation_data_decam'
      info['datasetRef']        = 'master'
      info['cloneTime']         = 60 // XXX untested
      info['runTime']           = 60 // XXX untested
      break
    default:
      error "unknown datasetSlug: ${datasetSlug}"
  }

  return info
}

/**
 *  Record logs
 *
 * @param archiveDir String path to drp output products that should be
 * persisted
 * @param drpDir String path to validate_drp build dir from which to collect
 * build time logs and/or junit output.
 */
def void record(String archiveDir, String drpDir, String lsstswDir) {
  def archive = [
    "${archiveDir}/**/*",
    "${drpDir}/**/*.log",
    "${drpDir}/**/*.failed",
    "${drpDir}/**/pytest-*.xml",
    "${lsstswDir}/**/*",
  ]

  def reports = [
    "${drpDir}/**/pytest-*.xml",
  ]

  // convert to relative paths
  // https://gist.github.com/ysb33r/5804364
  def rootDir = new File(pwd())
  archive = archive.collect { it ->
    def fullPath = new File(it)
    rootDir.toPath().relativize(fullPath.toPath()).toFile().toString()
  }

  reports = reports.collect { it ->
    def fullPath = new File(it)
    rootDir.toPath().relativize(fullPath.toPath()).toFile().toString()
  }

  archiveArtifacts([
    artifacts: archive.join(', '),
    excludes: '**/*.dummy',
    allowEmptyArchive: true,
    fingerprint: true
  ])

  junit([
    testResults: reports.join(', '),
    allowEmptyResults: true,
  ])
} // record

/**
 * Build validate_drp
 *
 * @param homemDir String path to $HOME -- where to put dotfiles
 * @param drpDir String path to validate_drp (code)
 * @param runSlug String short name to describe this drp run
 */
def void buildDrp(
  String homeDir,
  String drpDir,
  String runSlug,
  String ciDir,
  String compiler
) {
  // keep eups from polluting the jenkins role user dotfiles
  withEnv([
    "HOME=${homeDir}",
    "EUPS_USERDATA=${homeDir}/.eups_userdata",
    "DRP_DIR=${drpDir}",
    "CI_DIR=${ciDir}",
    "LSST_JUNIT_PREFIX=${runSlug}",
    "LSST_COMPILER=${compiler}",
  ]) {
    util.bash '''
      cd "$DRP_DIR"

      SHOPTS=$(set +o)
      set +o xtrace

      source "${CI_DIR}/ccutils.sh"
      cc::setup_first "$LSST_COMPILER"

      source /opt/lsst/software/stack/loadLSST.bash
      setup -k -r .

      eval "$SHOPTS"

      scons
    '''
  } // withEnv
}

/**
 * XXX this monster should be moved into an external shell script.
 *
 * Run validate_drp driver script.
 *
 * @param drpDir String path to validate_drp (code)
 * @param runDir String runtime cwd for validate_drp
 * @param dataset String full name of the validation dataset
 * @param datasetDir String path to validation dataset
 * @param datasetArhiveDir String path to persist valildation output products
 */
def void runDrp(
  String drpDir,
  String runDir,
  String dataset,
  String datasetDir,
  String datasetArchiveDir
) {
  // run drp driver script
  def run = {
    util.bash '''
      #!/bin/bash -e

      [[ $JENKINS_DEBUG == true ]] && set -o xtrace

      find_mem() {
        # Find system available memory in GiB
        local os
        os=$(uname)

        local sys_mem=""
        case $os in
          Linux)
            [[ $(grep MemAvailable /proc/meminfo) =~ \
               MemAvailable:[[:space:]]*([[:digit:]]+)[[:space:]]*kB ]]
            sys_mem=$((BASH_REMATCH[1] / 1024**2))
            ;;
          Darwin)
            # I don't trust this fancy greppin' an' matchin' in the shell.
            local free=$(vm_stat | grep 'Pages free:'     | \
              tr -c -d [[:digit:]])
            local inac=$(vm_stat | grep 'Pages inactive:' | \
              tr -c -d [[:digit:]])
            sys_mem=$(( (free + inac) / ( 1024 * 256 ) ))
            ;;
          *)
            >&2 echo "Unknown uname: $os"
            exit 1
            ;;
        esac

        echo "$sys_mem"
      }

      # find the maximum number of processes that may be run on the system
      # given the the memory per core ratio in GiB -- may be expressed in
      # floating point.
      target_cores() {
        local mem_per_core=${1:-1}

        local sys_mem=$(find_mem)
        local sys_cores
        sys_cores=$(getconf _NPROCESSORS_ONLN)

        # bash doesn't support floating point arithmetic
        local target_cores
        #target_cores=$(echo "$sys_mem / $mem_per_core" | bc)
        target_cores=$(awk "BEGIN{ print int($sys_mem / $mem_per_core) }")
        [[ $target_cores > $sys_cores ]] && target_cores=$sys_cores

        echo "$target_cores"
      }

      cd "$RUN_DIR"

      # do not xtrace (if set) into loadLSST.bash to avoid bloating the
      # jenkins console log
      SHOPTS=$(set +o)
      set +o xtrace
      source /opt/lsst/software/stack/loadLSST.bash
      setup -k -r "$DRP_DIR"
      setup -k -r "$DATASET_DIR"
      eval "$SHOPTS"

      case "$DATASET" in
        validation_data_cfht)
          RUN="$VALIDATE_DRP_DIR/examples/runCfhtTest.sh"
          RESULTS=(
            Cfht_output_r.json
          )
          LOGS=(
            'Cfht/singleFrame.log'
            'job_validate_drp.log'
          )
          ;;
        validation_data_decam)
          RUN="$VALIDATE_DRP_DIR/examples/runDecamTest.sh"
          RESULTS=(
            Decam_output_z.json
          )
          LOGS=(
            'Decam/singleFrame.log'
            'job_validate_drp.log'
          )
          ;;
        validation_data_hsc)
          RUN="$VALIDATE_DRP_DIR/examples/runHscTest.sh"
          RESULTS=(
            Hsc_output_HSC-I.json
            Hsc_output_HSC-R.json
            Hsc_output_HSC-Y.json
          )
          LOGS=(
            'Hsc/singleFrame.log'
            'job_validate_drp.log'
          )
          ;;
        *)
          >&2 echo "Unknown DATASET: ${DATASET}"
          exit 1
          ;;
      esac

      # pipe_drivers mpi implementation uses one core for orchestration, so we
      # need to set NUMPROC to the number of cores to utilize + 1
      MEM_PER_CORE=2.0
      export NUMPROC=$(($(target_cores $MEM_PER_CORE) + 1))

      set +e
      "$RUN" -- --noplot
      run_status=$?
      set -e

      echo "${RUN##*/} - exit status: ${run_status}"

      # archive drp processing results
      # process artifacts *before* bailing out if the drp run failed
      mkdir -p "$DATASET_ARCHIVE_DIR"
      artifacts=( "${RESULTS[@]}" "${LOGS[@]}" )

      for r in "${artifacts[@]}"; do
        dest="${DATASET_ARCHIVE_DIR}/${r##*/}"
        # file may not exist due to an error
        if [[ ! -e "${RUN_DIR}/${r}" ]]; then
          continue
        fi
        if ! cp "${RUN_DIR}/${r}" "$dest"; then
          continue
        fi
        # compressing an example hsc output file
        # (cmd)       (ratio)  (time)
        # xz -T0      0.183    0:20
        # xz -T0 -9   0.180    1:23
        # xz -T0 -9e  0.179    1:28
        xz -T0 -9ev "$dest"
      done

      # bail out if the drp output file is missing
      if [[ ! -e  "${RUN_DIR}/${RESULTS[0]}" ]]; then
        echo "drp result file does not exist: ${RUN_DIR}/${RESULTS[0]}"
        exit 1
      fi

      # XXX we are currently only submitting one filter per dataset
      ln -sf "${RUN_DIR}/${RESULTS[0]}" "${RUN_DIR}/output.json"

      exit $run_status
    '''
  } // run

  withEnv([
    "DRP_DIR=${drpDir}",
    "RUN_DIR=${runDir}",
    "DATASET=${dataset}",
    "DATASET_DIR=${datasetDir}",
    "DATASET_ARCHIVE_DIR=${datasetArchiveDir}",
    "JENKINS_DEBUG=true",
  ]) {
    run()
  }
}

/**
 * push DRP results to squash using dispatch-verify.
 *
 * @param resultPath
 * @param lsstswDir String Path to (the fake) lsstsw dir
 * @param datasetSlug String The dataset "short" name.  Eg., cfht instead of
 * validation_data_cfht.
 * @param noPush Boolean if true, do not attempt to push data to squash.
 * Reguardless of that value, the output of the characterization report is recorded
 */
def void runDispatchqa(
  String runDir,
  String drpDir,
  String archiveDir,
  String lsstswDir,
  String datasetSlug,
  Boolean noPush = true
) {

  def run = {
    util.bash '''
      source /opt/lsst/software/stack/loadLSST.bash
      cd "$DRP_DIR"
      setup -k -r .
      cd "$RUN_DIR"

      # compute characterization report
      reportPerformance.py \
        --output_file="$dataset"_char_report.rst \
        *_output_*.json
      cp "$dataset"_char_report.rst "$ARCH_DIR"
      xz -T0 -9ev "$ARCH_DIR"/"$dataset"_char_report.rst
    '''

    if (!noPush) {
      util.bash '''
        source /opt/lsst/software/stack/loadLSST.bash
        cd "$DRP_DIR"
        setup -k -r .
        cd "$RUN_DIR"

        # submit via dispatch_verify
        # XXX endpoint hardcoded until production SQuaSH is ready
        for file in $( ls *_output_*.json ); do
          dispatch_verify.py \
            --env jenkins \
            --lsstsw "$LSSTSW_DIR" \
            --url "$SQUASH_URL" \
            --user "$SQUASH_USER" \
            --password "$SQUASH_PASS" \
            $file
        done
      '''
    }
  } // run

  /*
  These are already present under pipeline:
  - BUILD_ID
  - BUILD_URL

  This var was defined automagically by matrixJob and now must be manually
  set:
  - dataset
  */
  withEnv([
    "LSSTSW_DIR=${lsstswDir}",
    "RUN_DIR=${runDir}",
    "DRP_DIR=${drpDir}",
    "ARCH_DIR=${archiveDir}",
    "NO_PUSH=${noPush}",
    "dataset=${datasetSlug}",
    "SQUASH_URL=${sqre.squash.url}",
  ]) {
    withCredentials([[
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: 'squash-api-user',
      usernameVariable: 'SQUASH_USER',
      passwordVariable: 'SQUASH_PASS',
    ]]) {
      run()
    } // withCredentials
  } // withEnv
}

/**
 * Download URL resource and write it to disk.
 *
 * @param url String URL to fetch
 * @param destFile String path to write downloaded file
 */
def void downloadFile(String url, String destFile) {
  writeFile(file: destFile, text: new URL(url).getText())
}

/**
 * Download `manifest.txt` from `lsst/versiondb`.
 *
 * @param destFile String path to write downloaded file
 * @param manifestId String manifest build id aka bNNNN
 */
def void downloadManifest(String destFile, String manifestId) {
  def manifestUrl = util.versiondbManifestUrl(manifestId)
  downloadFile(manifestUrl, destFile)
}

/**
 * Download a copy of `repos.yaml`
 *
 * @param destFile String path to write downloaded file
 */
def void downloadRepos(String destFile) {
  def reposUrl = util.reposUrl()
  downloadFile(reposUrl, destFile)
}
