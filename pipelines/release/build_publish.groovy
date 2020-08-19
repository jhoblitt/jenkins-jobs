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
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  util.requireParams([
    'REFS',
    'EUPS_TAG',
    'PRODUCTS',
    'BUILD_DOCS',
  ])

  String refs       = params.REFS
  String eupsTag    = params.EUPS_TAG
  String products   = params.PRODUCTS
  Boolean buildDocs = params.BUILD_DOCS

  def canonical    = scipipe.canonical
  def lsstswConfig = canonical.lsstsw_config

  def splenvRef = lsstswConfig.splenv_ref
  if (params.SPLENV_REF) {
    splenvRef = params.SPLENV_REF
  }

  echo "refs: ${refs}"
  echo "[eups] tag: ${eupsTag}"
  echo "products: ${products}"
  echo "build docs: ${buildDocs}"
  echo "scipipe_conda_env ref: ${splenvRef}"

  def retries = 3

  def manifestId = null

  def run = {
    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            REFS: refs,
            PRODUCTS: products,
            BUILD_DOCS: buildDocs,
            SPLENV_REF: splenvRef,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      retry(retries) {
        util.runPublish(
          parameters: [
            EUPSPKG_SOURCE: 'git',
            MANIFEST_ID: manifestId,
            EUPS_TAG: eupsTag,
            PRODUCTS: products,
            SPLENV_REF: splenvRef,
          ],
        )
      }
    } // stage
  } // run

  try {
    timeout(time: 30, unit: 'HOURS') {
      run()
    }
  } finally {
    stage('archive') {
      def resultsFile = 'results.json'

      util.nodeTiny {
        util.dumpJson(resultsFile, [
          manifest_id: manifestId ?: null,
          git_tag: refs ?: null,
          eups_tag: eupsTag ?: null,
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    } // stage
  } // try
} // notify.wrap
