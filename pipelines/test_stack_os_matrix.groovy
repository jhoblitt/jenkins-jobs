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
    util = load 'pipelines/lib/util_test.groovy'
    scipipe = util.scipipeConfig() // needed for side effects
    sqre = util.sqreConfig() // needed for side effects
  }
}

notify.wrap {
  // env vars are used instead of the params object so that "params" can be
  // set statically as env vars in calling jobs without exposing a "job param"
  // in the jenkins ui.
  util.requireEnvVars([
    'REFS',
    'BUILD_CONFIG',
    'PRODUCTS',
    'BUILD_DOCS',
    'PUBLISH_DOCS',
    'WIPEOUT',
  ])

  def buildParams = [
    LSST_REFS:      REFS,
    LSST_PRODUCTS:  PRODUCTS,
    LSST_BUILD_DOCS: BUILD_DOCS,
    LSST_PUBLISH_DOCS: PUBLISH_DOCS,
    LSST_SPLENV_REF: SPLENV_REF
  ]

  // if provided fork parameters, add them to the build params
  if (params.RUBINENV_ORG_FORK != null) {
    buildParams['RUBINENV_ORG_FORK'] = params.RUBINENV_ORG_FORK
  }
  if (params.RUBINENV_BRANCH != null) {
    buildParams['RUBINENV_BRANCH'] = params.RUBINENV_BRANCH
  }

  def lsstswConfigs = scipipe[BUILD_CONFIG]
  if (lsstswConfigs == null) {
    error "invalid value for BUILD_CONFIG: ${BUILD_CONFIG}"
  }

  timeout(time: 12, unit: 'HOURS') {
    stage('build') {
      util.lsstswBuildMatrix(lsstswConfigs, buildParams, WIPEOUT.toBoolean())
    }
  }
} // notify.wrap
