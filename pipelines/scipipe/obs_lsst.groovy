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
    obs_lsst = util.obsLsstConfig()
  }
}

notify.wrap {
  util.requireParams([
    'DOCKER_IMAGE',
    'WIPEOUT',
  ])

  String dockerImage = params.DOCKER_IMAGE
  Boolean wipeout    = params.WIPEOUT

  // run multiple datasets, if defined, in parallel
  def jobConf     = obs_lsst
  def jobConfName = 'obs_lsst'
  def matrix      = [:]
  def defaults    = jobConf."$jobConfName".defaults
  jobConf."$jobConfName".configs.each { conf ->
    // apply defaults
    conf = defaults + conf
    if (conf.code) {
      conf.code.display_name = displayName(conf.code)
    }
    conf.dataset.display_name = displayName(conf.dataset)

    // note that `:` seems to break python imports and `*` seems to break the
    // butler
    def runSlug = "${datasetSlug(conf)}^${codeSlug(conf)}"

    matrix[runSlug] = {
      obsLsstDataset(
        config: conf,
        dockerImage: dockerImage,
        slug: runSlug,
        wipeout: wipeout,
      )
    }
  }

  stage("${jobConfName} matrix") {
    parallel(matrix)
  }
} // notify.wrap

/**
 * Generate a "slug" to describe this dataset including branch name.
 *
 * @param conf Map
 */
def String datasetSlug(Map conf) {
  def ds = conf.dataset
  def slug = "${ds.display_name}-${ds.git_ref.tr('/', '_')}"
  return slug.toLowerCase()
}

/**
 * Generate a "slug" to describe the obs_lsst code including branch name.
 *
 * @param c Map
 */
def String codeSlug(Map conf) {
  def code = conf.code

  def name = 'obs_lsst'
  def ref = 'installed'

  if (code) {
    name = code.display_name
  }
  if (code?.github_repo) {
    name = code.name
    ref  = code.git_ref.tr('/', '_')
  }
  def slug = "${name}-${ref}".toLowerCase()
  return slug.toLowerCase()
}

/**
 * `name` to use to refer to dataset/code
 *
 * @param m Map
 */
def String displayName(Map m) {
  m.display_name ?: m.name
}

/**
 * Prepare, execute, and record results of a obs_lsst CI run.
 *
 * @param p Map
 * @param p.config Map Dataset configuration.
 * @param p.dockerImage String
 * @param p.slug String Name of dataset.
 * @param p.wipeout Boolean
 */
def void obsLsstDataset(Map p) {
  util.requireMapKeys(p, [
    'config',
    'dockerImage',
    'slug',
    'wipeout',
  ])

  def conf = p.config
  def ds   = conf.dataset
  def code = conf.code

  // code.name is required in order to build code
  Boolean buildCode = code?.name

  def run = {
    // note that pwd() must be run inside of a node {} block
    def jobDir           = pwd()
    def datasetDir       = "${jobDir}/datasets/${ds.name}"
    def ciDir            = "${jobDir}/ci-scripts"
    def baseDir          = "${jobDir}/${p.slug}"
    // the code clone needs to be under the long winded path for archiving
    def codeDir          = buildCode ? "${baseDir}/${code.name}" : ''
    def homeDir          = "${baseDir}/home"
    def runDir           = "${baseDir}/run"
    def fakeLsstswDir    = "${baseDir}/lsstsw-fake"

    docker.image(p.dockerImage).pull()
    def labels = util.shJson """
      docker inspect --format '{{json .Config.Labels }}' ${p.dockerImage}
    """

    if (!labels.VERSIONDB_MANIFEST_ID) {
      missingDockerLabel 'VERSIONDB_MANIFEST_ID'
    }
    if (!labels.LSST_COMPILER) {
      missingDockerLabel 'LSST_COMPILER'
    }

    String manifestId = labels.VERSIONDB_MANIFEST_ID
    String lsstCompiler = labels.LSST_COMPILER

    // empty ephemeral dirs at start of build
    util.emptyDirs([
      homeDir,
      runDir,
    ])

    // stage manifest.txt early so we don't risk a long processing run and
    // then fail setting up to run dispatch_verify.py
    stageFakeLsstsw(
      fakeLsstswDir: fakeLsstswDir,
      manifestId: manifestId,
      archiveDir: jobDir,
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    // clone dataset
    dir(datasetDir) {
      timeout(time: ds.clone_timelimit, unit: 'MINUTES') {
        util.checkoutLFS(
          githubSlug: ds.github_repo,
          gitRef: ds.git_ref,
        )
      } // timeout
    } // dir

    // clone code
    if (buildCode) {
      dir(codeDir) {
        timeout(time: code.clone_timelimit, unit: 'MINUTES') {
          // the simplier git step doesn't support 'CleanBeforeCheckout'
          def codeRepoUrl = util.githubSlugToUrl(code.github_repo)
          def codeRef     = code.git_ref

          checkout(
            scm: [
              $class: 'GitSCM',
              branches: [[name: "*/${codeRef}"]],
              doGenerateSubmoduleConfigurations: false,
              extensions: [[$class: 'CleanBeforeCheckout']],
              submoduleCfg: [],
              userRemoteConfigs: [[url: codeRepoUrl]]
            ],
            changelog: false,
            poll: false,
          )
        } // timeout
      } // dir
    }

    // process dataset
    util.insideDockerWrap(
      image: p.dockerImage,
      pull: true,
      args: "-v ${datasetDir}:${datasetDir}",
    ) {
      if (buildCode) {
        buildObsLsst(
          codeDir: codeDir,
          ciDir: ciDir,
          homeDir: homeDir,
          runSlug: p.slug,
          lsstCompiler: lsstCompiler,
          archiveDir: jobDir,
        )
      }

      runObsLsst(
        runDir: runDir,
        dataset: ds,
        datasetDir: datasetDir,
        homeDir: homeDir,
        archiveDir: jobDir,
        codeDir: codeDir,
      )
    } // insideDockerWrap
  } // run

  retry(conf.retries) {
    node('docker') {
      // total allowed runtime for this "try" including cloning a test data /
      // git-lfs repo
      timeout(time: conf.run_timelimit, unit: 'MINUTES') {
        if (p.wipeout) {
          deleteDir()
        }

        run()
      } // timeout
    } // node
  } // retry
} // obsLsstDataset

/**
 * Build obs_lsst 
 *
 * @param p Map
 * @param p.homemDir String path to $HOME -- where to put dotfiles
 * @param p.codeDir String path to validate_drp (code)
 * @param p,runSlug String short name to describe this drp run
 * @param p.ciDir String
 * @param p.lsstCompiler String
 * @param p.archiveDir String path from which to archive artifacts
 */
def void buildObsLsst(Map p) {
  util.requireMapKeys(p, [
    'homeDir',
    'codeDir',
    'runSlug',
    'ciDir',
    'lsstCompiler',
    'archiveDir',
  ])

  def run = {
    util.bash '''
      set +o xtrace

      source "${CI_DIR}/ccutils.sh"
      cc::setup_first "$LSST_COMPILER"

      source /opt/lsst/software/stack/loadLSST.bash
      setup -k -r .

      set -o xtrace

      scons
    '''
  }

  withEnv([
    "HOME=${p.homeDir}",
    // keep eups from polluting the jenkins role user dotfiles
    "EUPS_USERDATA=${p.homeDir}/.eups_userdata",
    "CODE_DIR=${p.codeDir}",
    "CI_DIR=${p.ciDir}",
    "LSST_JUNIT_PREFIX=${p.runSlug}",
    "LSST_COMPILER=${p.lsstCompiler}",
  ]) {
    try {
      dir(p.codeDir) {
        run()
      }
    } finally {
      dir(p.archiveDir) {
        util.record([
          "${p.codeDir}/**/*.log",
          "${p.codeDir}/**/*.failed",
          "${p.codeDir}/**/pytest-*.xml",
        ])
        util.junit(["${p.codeDir}/**/pytest-*.xml"])
      }
    } // try
  } // withEnv
}

/**
 * Run obs_lsst driver script.
 *
 * @param p Map
 * @param p.runDir String runtime cwd
 * @param p.dataset String full name of the validation dataset
 * @param p.datasetDir String path to validation dataset
 * @param p.homemDir String path to $HOME -- where to put dotfiles
 * @param p.archiveDir String path from which to archive artifacts
 */
def void runObsLsst(Map p) {
  util.requireMapKeys(p, [
    'runDir',
    'dataset',
    'datasetDir',
    'homeDir',
    'archiveDir',
    'codeDir',
  ])

  def run = {
    util.bash '''
      source /opt/lsst/software/stack/loadLSST.bash
      # if CODE_DIR is defined, set that up instead of the default obs_lsst
      # product
      echo "This is CODE_DIR ${CODE_DIR}"
      if [[ -n $CODE_DIR ]]; then
        setup -k -r "$CODE_DIR"
      else
        setup obs_lsst
      fi

      cd ${DATASET_DIR}
      setup -k -r .

      cd ${RUN_DIR}
      runPipeline --raw $TESTDATA_OBS_LSST_DIR/raws --dir WORK
    '''
  }

  // run obs_lsst CI script
  withEnv([
    "RUN_DIR=${p.runDir}",
    "DATASET_NAME=${p.dataset.name}",
    "DATASET_DIR=${p.datasetDir}",
    "HOME=${p.homeDir}",
    "CODE_DIR=${p.codeDir}",
  ]) {
    try {
      dir(p.runDir) {
        run()
      }
    } finally {
      dir(p.archiveDir) {
        util.record(util.xz([
          "${p.runDir}/**/*.log",
        ]))
      }
    } // try
  } // withEnv
} // runObsLsst

def void missingDockerLabel(String label) {
  error "docker ${label} label is missing"
}

/**
 * bootstrap a fake lsstsw clone and archive it
 *
 * @param p Map
 * @param p.fakeLsstswDir String path to the "clone"
 * @param p.manifestId String versiondb manifest id
 * @param p.archiveDir String path from which to archive artifacts
 */
def void stageFakeLsstsw(Map p) {
  util.requireMapKeys(p, [
    'fakeLsstswDir',
    'manifestId',
    'archiveDir',
  ])

  try {
    util.createFakeLsstswClone(
      fakeLsstswDir: p.fakeLsstswDir,
      manifestId: p.manifestId,
    )
  } finally {
    dir(p.archiveDir) {
      util.record(["${p.fakeLsstswDir}/**"])
    }
  } // try
} // stageFakeLsstsw
