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
    scipipe = util.scipipeConfig() // side effect only
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  util.requireParams([
    'COMPILER',
    'EUPS_TAG',
    'IMAGE',
    'LABEL',
    'SPLENV_REF',
    'RUBINENV_VER',
    'MINIVER',
    'OSFAMILY',
    'PLATFORM',
    'PRODUCTS',
    'PUBLISH',
    'PYTHON_VERSION',
    'RUN_SCONS_CHECK',
    'SMOKE',
    'TIMEOUT',
    'WIPEOUT',
  ])

  String eupsTag        = params.EUPS_TAG
  String image          = util.emptyToNull(params.IMAGE) // '' means null
  String label          = params.LABEL
  String splenvRef      = params.SPLENV_REF
  String rubinEnvVer    = params.RUBINENV_VER
  String miniver        = params.MINIVER
  String products       = params.PRODUCTS
  String osfamily       = params.OSFAMILY
  String platform       = params.PLATFORM
  Boolean publish       = params.PUBLISH
  String pythonVersion  = params.PYTHON_VERSION
  Boolean runSconsCheck = params.RUN_SCONS_CHECK
  Boolean smoke         = params.SMOKE
  Integer timeout       = params.TIMEOUT
  Boolean wipeout       = params.WIPEOUT

  def py = new MinicondaEnv(pythonVersion,miniver, splenvRef, rubinEnvVer)

  def buildTarget = [
    products: products,
    eups_tag: eupsTag,
  ]

  def smokeConfig = null
  if (smoke) {
    smokeConfig = [
      run_scons_check: runSconsCheck,
    ]
  }

  switch(osfamily) {
    case 'redhat':
      linuxTarballs(image, platform, compiler, py,
        timeout, buildTarget, smokeConfig, wipeout, publish)
      break
    case 'osx':
      osxTarballs(label, platform, compiler, py,
        timeout, buildTarget, smokeConfig, wipeout, publish)
      break
    default:
      error "unsupported platform: ${label}"
  }
} // notify.wrap

/**
 * Build EUPS tarballs inside of a docker container.
 *
 * @param imageName docker image slug
 * @param platform Eg., 'el7'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param timelimit Integer build timeout in hours
 * @param buildTarget Map
 * @param buildTarget.products String
 * @param buildTarget.eups_tag String
 * @param smoke Map `null` disables running a smoke test
 * @param smoke.run_scons_check Boolean
 * @param wipeout Boolean
 * @param publish Boolean
 */
def void linuxTarballs(
  String imageName,
  String platform,
  String compiler,
  MinicondaEnv menv,
  Integer timelimit,
  Map buildTarget,
  Map smokeConfig,
  Boolean wipeout = false,
  Boolean publish = false
) {
  def String slug = menv.slug()
  def envId = util.joinPath('redhat', platform, compiler, slug)
  def buildDirHash = util.hashpath(envId)

  def run = {
    if (wipeout) {
      deleteDir()
    }

    // these "credentials" aren't secrets -- just a convient way of setting
    // globals for the instance. Thus, they don't need to be tightly scoped to a
    // single sh step
    util.withEupsEnv {
      dir(buildDirHash.take(10)) {
        stage("build ${envId}") {
          docker.image(imageName).pull()
          linuxBuild(imageName, compiler, menv, buildTarget)
        }
        stage('smoke') {
          if (smokeConfig) {
            linuxSmoke(imageName, compiler, menv, buildTarget, smokeConfig)
          }
        }

        stage('publish') {
          if (publish) {
            s3PushDocker(envId)
          }
        }
      }
    } // util.withEupsEnv
  } // run()

  util.nodeWrap('docker') {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
}

/**
 * Build EUPS tarballs in a regular directory.
 *
 * @param label String jenkins node label
 * @param macosx_deployment_target Eg., '10.9'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param timelmit Integer build timeout in hours
 * @param buildTarget Map
 * @param buildTarget.products String
 * @param buildTarget.eups_tag String
 * @param smoke Map `null` disables running a smoke test
 * @param smoke.run_scons_check Boolean
 * @param wipeout Boolean
 * @param publish Boolean
 */
def void osxTarballs(
  String label,
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv,
  Integer timelimit,
  Map buildTarget,
  Map smokeConfig,
  Boolean wipeout = false,
  Boolean publish = false
) {
  def String slug = menv.slug()
  def envId = util.joinPath('osx', macosx_deployment_target, compiler, slug)
  def buildDirHash = util.hashpath(envId)

  def run = {
    if (wipeout) {
      deleteDir()
    }

    // these "credentials" aren't secrets -- just a convient way of setting
    // globals for the instance. Thus, they don't need to be tightly scoped to a
    // single sh step
    util.withEupsEnv {
      dir(buildDirHash.take(10)) {
        stage('build') {
          osxBuild(macosx_deployment_target, compiler, menv, buildTarget)
        }

        stage('smoke') {
          if (smokeConfig) {
            osxSmoke(
              macosx_deployment_target,
              compiler,
              menv,
              buildTarget,
              smokeConfig
            )
          }
        } //stage

        stage('publish') {
          if (publish) {
            s3PushDocker(envId)
          }
        }
      } // dir
    } // util.withEupsEnv
  } // run

  util.nodeWrap(label) {
    timeout(time: timelimit, unit: 'HOURS') {
      run()
    }
  }
}

/**
 * Run Linux specific tarball build.
 *
 * @param imageName docker image slug
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param buildTarget Map
 * @param buildTarget.products String
 * @param buildTarget.eups_tag String
 */
def void linuxBuild(
  String imageName,
  String compiler,
  MinicondaEnv menv,
  Map buildTarget
) {
  def cwd      = pwd()
  def buildDir = "${cwd}/build"
  def distDir  = "${cwd}/distrib"
  def shDir    = "${buildDir}/scripts"
  def ciDir    = "${cwd}/ci-scripts"

  def buildDirContainer = '/build'
  def distDirContainer  = '/distrib'
  def ciDirContainer    = '/ci-scripts'

  def shBasename = 'run.sh'
  def shName = "${shDir}/${shBasename}"
  def localImageName = "${imageName}-local"

  try {
    util.createDirs([
      buildDir,
      distDir,
      shDir,
    ])

    // sanitize build dir to ensure log collection is for the current build
    // only
    emptyExistingDir(eupsBuildDir(buildDir, menv))

    prepareBuild(
      buildTarget.products,
      buildTarget.eups_tag,
      shName,
      distDirContainer,
      compiler,
      null,
      menv,
      ciDirContainer
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    util.wrapDockerImage(
      image: imageName,
      tag: localImageName,
      pull: true,
    )

    withEnv([
      "RUN=/build/scripts/${shBasename}",
      "IMAGE=${localImageName}",
      "BUILDDIR=${buildDir}",
      "BUILDDIR_CONTAINER=${buildDirContainer}",
      "DISTDIR=${distDir}",
      "DISTDIR_CONTAINER=${distDirContainer}",
      "CIDIR=${ciDir}",
      "CIDIR_CONTAINER=${ciDirContainer}",
    ]) {
      // XXX refactor to use util.insideDockerWrap
      util.bash '''
        docker run \
          -v "${BUILDDIR}:${BUILDDIR_CONTAINER}" \
          -v "${DISTDIR}:${DISTDIR_CONTAINER}" \
          -v "${CIDIR}:${CIDIR_CONTAINER}" \
          -w /build \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          -u "$(id -u -n)" \
          "$IMAGE" \
          bash -c "$RUN"
      '''
    } // withEnv
  } finally {
    record(buildDir, menv)
    cleanup(buildDir)
  }
} // linuxBuild

/**
 * Run OSX specific tarball build.
 *
 * @param macosx_deployment_target Eg., '10.9'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param buildTarget Map
 * @param buildTarget.products String
 * @param buildTarget.eups_tag String
 */
def void osxBuild(
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv,
  Map buildTarget
) {
  def cwd      = pwd()
  def buildDir = "${cwd}/build"
  def distDir  = "${cwd}/distrib"
  def shDir    = "${buildDir}/scripts"
  def ciDir    = "${cwd}/ci-scripts"

  def shName = "${shDir}/run.sh"

  try {
    util.createDirs([
      buildDir,
      distDir,
      shDir,
    ])

    // sanitize build dir to ensure log collection is for the current build
    // only
    emptyExistingDir(eupsBuildDir(buildDir, menv))

    prepareBuild(
      buildTarget.products,
      buildTarget.eups_tag,
      "${shName}",
      distDir,
      compiler,
      macosx_deployment_target,
      menv,
      ciDir
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    dir(buildDir) {
      util.bash shName
    }
  } finally {
    record(buildDir, menv)
    cleanup(buildDir)
  }
} // osxBuild

/**
 * Run Linux specific tarball smoke test(s).
 *
 * @param imageName docker image slug
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param buildTarget Map
 * @param buildTarget.products String
 * @param buildTarget.eups_tag String
 * @param smoke Map
 * @param smoke.run_scons_check Boolean
 */
def void linuxSmoke(
  String imageName,
  String compiler,
  MinicondaEnv menv,
  Map buildTarget,
  Map smokeConfig
) {
  def cwd      = pwd()
  def smokeDir = "${cwd}/smoke"
  def distDir  = "${cwd}/distrib"
  def shDir    = "${smokeDir}/scripts"
  def ciDir    = "${cwd}/ci-scripts"

  def smokeDirContainer = '/smoke'
  def distDirContainer  = '/distrib'
  def ciDirContainer    = '/ci-scripts'

  def shBasename = 'run.sh'
  def shName = "${shDir}/${shBasename}"
  def localImageName = "${imageName}-local"

  try {
    // smoke state is left at the end of the build for possible debugging but
    // each test needs to be run in a clean env.
    util.emptyDirs([smokeDir])

    prepareSmoke(
      buildTarget.products,
      buildTarget.eups_tag,
      shName,
      distDirContainer,
      compiler,
      null,
      menv,
      ciDirContainer
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    util.wrapDockerImage(
      image: imageName,
      tag: localImageName,
      pull: true,
    )

    withEnv([
      "RUN=/smoke/scripts/${shBasename}",
      "IMAGE=${localImageName}",
      "RUN_SCONS_CHECK=${smokeConfig.run_scons_check}",
      "SMOKEDIR=${smokeDir}",
      "SMOKEDIR_CONTAINER=${smokeDirContainer}",
      "DISTDIR=${distDir}",
      "DISTDIR_CONTAINER=${distDirContainer}",
      "CIDIR=${ciDir}",
      "CIDIR_CONTAINER=${ciDirContainer}",
    ]) {
      // XXX refactor to use util.insideDockerWrap
      util.bash '''
        docker run \
          -v "${SMOKEDIR}:${SMOKEDIR_CONTAINER}" \
          -v "${DISTDIR}:${DISTDIR_CONTAINER}" \
          -v "${CIDIR}:${CIDIR_CONTAINER}" \
          -w /smoke \
          -e EUPS_S3_BUCKET="$EUPS_S3_BUCKET" \
          -e RUN_SCONS_CHECK="$RUN_SCONS_CHECK" \
          -e FIX_SHEBANGS=true \
          -u "$(id -u -n)" \
          "$IMAGE" \
          bash -c "$RUN"
      '''
    } // withEnv
  } finally {
    record(smokeDir, menv)
  }
} // linuxSmoke

/**
 * Generate + write build script.
 *
 * @param macosx_deployment_target Eg., '10.9'
 * @param compiler Eg., 'system-gcc'
 * @param menv Miniconda object
 * @param buildTarget Map
 * @param buildTarget.products String
 * @param buildTarget.eups_tag String
 * @param smoke Map
 * @param smoke.run_scons_check Boolean
 */
def void osxSmoke(
  String macosx_deployment_target,
  String compiler,
  MinicondaEnv menv,
  Map buildTarget,
  Map smokeConfig
) {
  def cwd      = pwd()
  def smokeDir = "${cwd}/smoke"
  def shName   = "${cwd}/scripts/smoke.sh"
  def ciDir    = "${cwd}/ci-scripts"

  try {
    // smoke state is left at the end of the build for possible debugging but
    // each test needs to be run in a clean env.
    dir(smokeDir) {
      deleteDir()
    }

    prepareSmoke(
      buildTarget.products,
      buildTarget.eups_tag,
      shName,
      "${cwd}/distrib",
      compiler,
      macosx_deployment_target,
      menv,
      ciDir
    )

    dir(ciDir) {
      util.cloneCiScripts()
    }

    dir(smokeDir) {
      withEnv([
        "RUN_SCONS_CHECK=${smokeConfig.run_scons_check}",
        "FIX_SHEBANGS=true",
      ]) {
        util.bash shName
      }
    }
  } finally {
    record(smokeDir, menv)
  }
} // osxSmoke

/**
 * Generate + write build script.
 */
def void prepareBuild(
  String products,
  String eupsTag,
  String shName,
  String distribDir,
  String compiler,
  String macosx_deployment_target,
  MinicondaEnv menv,
  String ciDir
) {
  def script = buildScript(
    products,
    eupsTag,
    distribDir,
    compiler,
    macosx_deployment_target,
    menv,
    ciDir
  )

  writeScript(file: shName, text: script)
}

/**
 * Generate + write smoke test script.
 */
def void prepareSmoke(
  String products,
  String eupsTag,
  String shName,
  String distribDir,
  String compiler,
  String macosx_deployment_target,
  MinicondaEnv menv,
  String ciDir
) {
  def script = smokeScript(
    products,
    eupsTag,
    distribDir,
    compiler,
    macosx_deployment_target,
    menv,
    ciDir
  )

  writeScript(file: shName, text: script)
}

/**
 * write executable file
 *
 * @param p Map
 * @param p.file String name of script file to write
 * @param p.text String script text
 */
def void writeScript(Map p) {
  echo "creating script ${p.file}:"
  echo p.text

  writeFile(file: p.file, text: p.text)
  util.bash "chmod a+x ${p.file}"
}

/**
 * Push {@code ./distrib} dir to an s3 bucket under the "path" formed by
 * joining the {@code parts} parameters.
 */
def void s3PushDocker(String ... parts) {
  def objectPrefix = "stack/" + util.joinPath(parts)
  def cwd = pwd()

  def env = [
    "EUPS_PKGROOT=${cwd}/distrib",
    "EUPS_S3_OBJECT_PREFIX=${objectPrefix}",
    "HOME=${cwd}/home",
  ]

  withEnv(env) {
    withEupsBucketEnv {
      docker.image(util.defaultAwscliImage()).inside {
        // alpine does not include bash by default
        util.posixSh(s3PushCmd())
      } // .inside
    } //withEupsBucketEnv
  } // withEnv
}

/**
 * Returns a shell command string for pushing the EUPS_PKGROOT to s3.
 *
 * @return String cmd
 */
def String s3PushCmd() {
  // do not interpolate now -- all values should come from the shell env.
  return util.dedent('''
    aws s3 cp \
      --only-show-errors \
      --recursive \
      "${EUPS_PKGROOT}/" \
      "s3://${EUPS_S3_BUCKET}/${EUPS_S3_OBJECT_PREFIX}"
  ''')
}

/**
 * Declares the following env vars from credentials:
 * - AWS_ACCESS_KEY_ID
 * - AWS_SECRET_ACCESS_KEY
 * - EUPS_S3_BUCKET
 */
def void withEupsBucketEnv(Closure run) {
  withCredentials([[
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'aws-eups-push',
    usernameVariable: 'AWS_ACCESS_KEY_ID',
    passwordVariable: 'AWS_SECRET_ACCESS_KEY'
  ]]) {
    util.withEupsEnv {
      run()
    }
  } // withCredentials
}

/**
 *  Record logs
 */
def void record(String buildDir, MinicondaEnv menv) {
  def eupsBuildDir = eupsBuildDir(buildDir, menv)

  def archive = [
    '**/*.log',
    '**/*.failed',
    '../../eupsbuild.log'
  ]

  def reports = [
    '**/pytest-*.xml',
  ]

  dir(eupsBuildDir) {
    archiveArtifacts([
      artifacts: archive.join(', '),
      allowEmptyArchive: true,
      fingerprint: true
    ])

    junit([
      testResults: reports.join(', '),
      allowEmptyResults: true,
    ])
  }
}

/**
 * Cleanup after a build attempt.
 */
def void cleanup(String buildDir) {
  dir("${buildDir}/.lockDir") {
    deleteDir()
  }
}

/**
 * Generate shellscript to build EUPS distrib tarballs.
 */
// XXX the dynamic build script construction has evolved into a fair number of
// nested steps and this may be difficult to comprehend in the future.
// Consider moving all of this logic into an external driver script that is
// called with parameters.
def String buildScript(
  String products,
  String tag,
  String eupsPkgroot,
  String compiler,
  String macosx_deployment_target,
  MinicondaEnv menv,
  String ciDir
) {
  scriptPreamble(
    compiler,
    macosx_deployment_target,
    menv,
    true,
    ciDir
  ) +
  util.dedent("""
    curl -sSL ${util.newinstallUrl()} | bash -s -- -cb
    . ./loadLSST.bash

    for prod in ${products}; do
      eups distrib install "\$prod" -t "${tag}" -vvv
    done

    export EUPS_PKGROOT="${eupsPkgroot}"

    # remove any pre-existing eups tags to prevent them from being
    # [re]published
    # the tarball pkgroots have tag files (.list) directly in the root of the
    # repo
    if [[ -e \$EUPS_PKGROOT ]]; then
      rm -f "\${EUPS_PKGROOT}/*.list"
    fi

    for prod in ${products}; do
      eups distrib create --server-dir "\$EUPS_PKGROOT" -d tarball "\$prod" -t "${tag}" -vvv
    done
    eups distrib declare --server-dir "\$EUPS_PKGROOT" -t "${tag}" -vvv

    # saving environment information
    mkdir -p "\${EUPS_PKGROOT}/env"
    conda list --explicit > "\${EUPS_PKGROOT}/env/${tag}.env"
  """)
}

/**
 * Generate shellscript to execute a "smoke" install test.
 */
def String smokeScript(
  String products,
  String tag,
  String eupsPkgroot,
  String compiler,
  String macosx_deployment_target,
  MinicondaEnv menv,
  String ciDir
) {
  def baseUrl = util.githubSlugToUrl("${scipipe.release_tag_org}/base")

  scriptPreamble(
    compiler,
    macosx_deployment_target,
    menv,
    true,
    ciDir
  ) +
   util.dedent("""
    export EUPS_PKGROOT="${eupsPkgroot}"
    export BASE_URL="${baseUrl}"

    curl -sSL ${util.newinstallUrl()} | bash -s -- -cb
    . ./loadLSST.bash

    # override newinstall.sh configured EUPS_PKGROOT
    export EUPS_PKGROOT="${eupsPkgroot}"

    for prod in ${products}; do
      eups distrib install "\$prod" -t "${tag}" -vvv
    done

    if [[ \$FIX_SHEBANGS == true ]]; then
      curl -sSL ${util.shebangtronUrl()} | python
    fi

  """ + '''
    #
    # use the same version of base that was just installed to rule out source
    # compatibility issues.
    #
    # match:
    # - 13 as 13
    # - 13.0 as 13.0
    # - 13.0+1 as 13.0
    # - 2.9.1.lsst1+1 as 2.9.1.lsst1
    # - 13.0-10-g692d0a9 as 692d0a9
    # - 13.0-10-g692d0a9+1 as 692d0a9
    # - master-gd7f6e4dbf2+24 as d7f6e4dbf2
    # - 3.11.lsst1-2-g6ae2b7a as 6ae2b7a
    #
    # Eg.
    #    13.0-10-692d0a9 d_2017_09_14 ... current d_2017_09_13
    #
    # note that py2.7 compat is required -- the lambda can be dropped under
    # py3.5+
    estring2ref() {
      python -c "
    import sys,re;
    for line in sys.stdin:
      foo = re.sub(r'^\\s*(?:[\\w.-]+g([a-zA-Z0-9]+)|([\\w.-]+))(?:\\+[\\dA-Fa-f]+)?\\s+.*', lambda m: m.group(1) or m.group(2), line)
      if foo is line:
        sys.exit(1)
      print(foo)
    "
    }

    if [[ \$RUN_SCONS_CHECK == true ]]; then
      BASE_REF=$(eups list base | estring2ref)

      # sadly, git will not clone by sha1 -- only branch/tag names are allowed
      git clone "$BASE_URL"
      cd base
      git checkout "$BASE_REF"
      setup -k -r .
      scons
    fi
  ''')
}

/**
 * Generate common shellscript boilerplate.
 */
def String scriptPreamble(
  String compiler,
  String macosx_deployment_target='10.9',
  MinicondaEnv menv,
  boolean useTarballs,
  String ciDir
) {
  util.dedent("""
    #!/bin/bash

    set -xe
    set -o pipefail

    if [[ -n \$EUPS_S3_BUCKET ]]; then
        export LSST_EUPS_PKGROOT_BASE_URL="https://\${EUPS_S3_BUCKET}/stack"
    fi

    # isolate eups cache files
    export EUPS_USERDATA="\${PWD}/.eups"

    # isolate conda config
    export CONDARC="\${PWD}/.condarc"

    if [[ \$(uname -s) == Darwin* ]]; then
      export MACOSX_DEPLOYMENT_TARGET="${macosx_deployment_target}"
    fi

    export LSST_PYTHON_VERSION="${menv.pythonVersion}"
    export LSST_MINICONDA_VERSION="${menv.minicondaVersion}"
    export LSST_SPLENV_REF="${menv.splenvRef}"
    export LSST_EUPS_USE_TARBALLS="${useTarballs}"
    export LSST_COMPILER="${compiler}"

    source "${ciDir}/ccutils.sh"
    cc::setup_first "\$LSST_COMPILER"
    """
  )
}

/**
 * Represents a miniconda build environment.
 */
class MinicondaEnv implements Serializable {
  String pythonVersion
  String minicondaVersion
  String splenvRef
  String rubinEnvVer

  /**
   * Constructor.
   *
   * @param p Python major version number. Eg., '3'
   * @param m Miniconda version string. Eg., '4.2.12'
   * @param l {@code lsst/lsstsw} git ref.
   * @return MinicondaEnv
   */
  // unfortunately, a constructor is required under the security sandbox
  // See: https://issues.jenkins-ci.org/browse/JENKINS-34741
  MinicondaEnv(String p, String m, String l, String v) {
    this.pythonVersion = p
    this.minicondaVersion = m
    this.splenvRef = l
    this.rubinEnvVer = v
  }

  /**
   * Generate a single string description of miniconda env.
   */
  String slug() {
    "miniconda${pythonVersion}-${minicondaVersion}-${rubinEnvVer}"
  }
}

/**
 * Empty dir only if it exists.  This is intended to avoid the side effect of
 * the dir() step of creating an empty dir if it does not already exists.
 *
 * @param path String path to dir to empty, if it exists
 */
def void emptyExistingDir(String path) {
  if (fileExists(path)) {
    dir(path) {
      deleteDir()
    }
  }
}

/**
 * Calculate EupsBuildDir path
 *
 * @param buildDir String root path to newinstall.sh env
 * @param menv MinicondaEnv
 * @return String path to EupsBuildDir
 */
def String eupsBuildDir(String buildDir, MinicondaEnv menv) {
  return "${buildDir}/stack/${menv.slug()}/EupsBuildDir"
}
