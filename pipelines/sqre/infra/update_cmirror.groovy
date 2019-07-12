import groovy.transform.Field

@Field String mirrorBaseUrl = 'https://repo.continuum.io/pkgs'

node('jenkins-master') {
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
    sqre = util.sqreConfig()
  }
}

notify.wrap {
  def retries = 3

  def channels = [
    'main',
    'free',
  ]

  def platforms = [
    'linux-64',
    'osx-64',
    'noarch',
  ]

  def run = {
    def mirrorConfig = [:]

    // mirror product of channels * platforms
    channels.each { c ->
      platforms.each { p ->
        mirrorConfig["${c}/${p}"] = {
          util.nodeWrap('docker') {
            timeout(time: 3, unit: 'HOURS') {
              mirrorCondaChannel(
                channel: c,
                platform: p,
                retries: retries,
              )
            }
          } // util.nodeWrap
        } // mirrorConfig
      } // platforms
    } // channels

    mirrorConfig['miniconda installers'] = {
      util.nodeWrap('docker') {
        timeout(time: 1, unit: 'HOURS') {
          mirrorMinicondaInstallers(retries: retries)
        }
      } // util.nodeWrap
    } // mirrorConfig

    stage('mirror') {
      parallel mirrorConfig
    }
  } // run

  // the timeout should be <= the cron triggering interval to prevent builds
  // pilling up in the backlog.
  timeout(time: 23, unit: 'HOURS') {
    run()
  }
} // notify.wrap

/**
 * Mirror one platform of a conda channel
 *
 * @param p Map
 * @param p.channel String name of conda channel. Eg., `main`, `free`
 * @param p.platform String name of conda platform. Eg., `linux-64', `noarch`
 * @param p.retries Integer defaults to `3`.
 */
def void mirrorCondaChannel(Map p) {
  util.requireMapKeys(p, [
    'channel',
    'platform',
  ])

  p = [
    retries: 3
  ] + p
  String channel  = p.channel
  String platform = p.platform
  Integer retries = p.retries

  def upstreamUrl = "${mirrorBaseUrl}/${channel}/"
  def channelDir  = "local_mirror/${channel}"
  def tmpDir      = "tmp/${channel}/${platform}"
  def repodataDir = "repodata/${channel}/${platform}"

  util.createDirs([
    // ensure destination mirror path exists
    // dir tree should be ./local_mirror/${channel}/${platform}
    channelDir,
    // tmp dir use for download of upstream files
    tmpDir,
  ])

  // archive a copy of the upstream repodata.json at (or as close to as is
  // possible) the time conda-mirror is run.  This may be useful for debugging
  // suspected repodata.json issues as conda-mirror completely rewrites the
  // packages section of this file.
  dir(repodataDir) {
    // cleanup download repodata.json files between builds
    deleteDir()

    retry(retries) {
      docker.image(defaultWgetImage()).inside {
        util.posixSh "wget --no-verbose ${upstreamUrl}${platform}/repodata.json"
      }
    }
  }

  archiveArtifacts([
    artifacts: "${repodataDir}/*",
    fingerprint: true,
  ])

  def doMirror = {
    retry(retries) {
      util.insideDockerWrap(
        image: defaultCmirrorImage(),
        pull: true,
      ) {
        util.bash '''
          /miniconda/bin/conda-mirror \
            --num-threads 0 \
            --upstream-channel "$UPSTREAM_URL" \
            --temp-directory "$TMP_DIR" \
            --target-directory "$CHANNEL_DIR" \
            --platform "$PLATFORM" \
            -vvv
        '''
      }
    } // retry

    // push local channel/platform mirror to s3
    withCmirrorCredentials {
      // XXX aws s3 sync appears to give up too easily on error and a
      // failure on a single object will cause the job to fail, so it
      // seems reasonable to retry the entire operation.
      // See: https://github.com/aws/aws-cli/issues/1092
      catchError {
        retry(retries) {
          docker.image(util.defaultAwscliImage()).inside {
            util.posixSh '''
              aws s3 cp \
                --only-show-errors \
                --recursive \
                "${CHANNEL_DIR}/${PLATFORM}" \
                "s3://${CMIRROR_S3_BUCKET}/pkgs/${CHANNEL}/${PLATFORM}"
            '''
          }
        } // retry
      } // catchError
    } // withCmirrorCredentials
  } // doMirror

  withEnv([
    "UPSTREAM_URL=${upstreamUrl}",
    "PLATFORM=${platform}",
    "CHANNEL=${channel}",
    "CHANNEL_DIR=${channelDir}",
    "TMP_DIR=${tmpDir}",
  ]) {
    doMirror()
  } // withEnv
} // mirrorCondaChannel

/**
 * Mirror miniconda installer packages
 *
 * @param p Map
 * @param p.retries Integer defaults to `3`.
 */
def void mirrorMinicondaInstallers(Map p) {
  p = [
    retries: 3
  ] + p
  Integer retries = p.retries

  retry(retries) {
    docker.image(defaultWgetImage()).inside {
      util.posixSh '''
        wget \
          --mirror \
          --no-verbose \
          --continue \
          --no-parent \
          --no-host-directories \
          --progress=dot:giga \
          -R "*.exe" \
          -R "*ppc64le.sh" \
          -R "*armv7l.sh" \
          -R "*x86.sh" \
          https://repo.continuum.io/miniconda/
      '''
    }
  } // retrey

  withCmirrorCredentials {
    catchError {
      retry(retries) {
        docker.image(util.defaultAwscliImage()).inside {
          util.posixSh '''
            aws s3 cp \
              --only-show-errors \
              --recursive \
              ./miniconda/ \
              "s3://${CMIRROR_S3_BUCKET}/miniconda/"
          '''
        }
      } // retry
    } // catchError
  } // withCmirrorCredentials
} // mirrorMinicondaInstallers

/**
 * Run block with "cmirror" credentials defined in env vars.
 *
 * Variables defined:
 * - AWS_ACCESS_KEY_ID
 * - AWS_SECRET_ACCESS_KEY
 * - CMIRROR_S3_BUCKET
 *
 * @param run Closure Invoked inside of node step
 */
def void withCmirrorCredentials(Closure run) {
  withCredentials([[
    $class: 'UsernamePasswordMultiBinding',
    credentialsId: 'aws-cmirror-push',
    usernameVariable: 'AWS_ACCESS_KEY_ID',
    passwordVariable: 'AWS_SECRET_ACCESS_KEY',
  ]]) {
    util.withCondaMirrorEnv {
      run()
    }
  }
}

def String defaultCmirrorImage() {
  def dockerRegistry = sqre.cmirror.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}

def String defaultWgetImage() {
  def dockerRegistry = sqre.wget.docker_registry
  "${dockerRegistry.repo}:${dockerRegistry.tag}"
}
