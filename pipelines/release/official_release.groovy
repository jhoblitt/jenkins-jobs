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
    scipipe = util.scipipeConfig()
    sqre = util.sqreConfig() // side effect only
  }
}

notify.wrap {
  util.requireParams([
    'SOURCE_GIT_REFS',
    'RELEASE_GIT_TAG',
    'O_LATEST',
  ])

  String sourceGitRefs = params.SOURCE_GIT_REFS
  String gitTag        = params.RELEASE_GIT_TAG
  Boolean dockerLatest = params.O_LATEST

  // generate eups tag from git tag
  String eupsTag = util.sanitizeEupsTag(gitTag)

  // determine if this is an rc release (does not start with an integer)
  Boolean finalRelease = !!(gitTag =~ /^\d/)

  // o_latest is only allowed for a final release
  if (dockerLatest && !finalRelease) {
    error "O_LATEST is only allowed on final (non-rc) releases"
  }

  // type of eupspkg to create -- "git" should always be used except for a
  // final (non-rc) release
  String eupspkgSource = finalRelease ? 'package' : 'git'

  // A final release git tag for a "regular" package does not start with a `v`.
  // However, the release git tag for an external packages is always prefixed
  // with a `v`.  Thus, in the case of a final release, we need to use both
  // v<tag> and <tag> as the source git refs to produce the eups source
  // packages.
  def buildGitTags = finalRelease ? "v${gitTag} ${gitTag}" : gitTag

  def lsstswConfig = scipipe.canonical.lsstsw_config

  echo "final release (non-rc): ${finalRelease}"
  echo "EUPSPKG_SOURCE: ${eupspkgSource}"
  echo "input git refs: ${sourceGitRefs}"
  echo "build git refs: ${buildGitTags}"
  echo "publish [git] tag: ${gitTag}"
  echo "publish [eups] tag: ${eupsTag}"

  def products        = scipipe.canonical.products
  def tarballProducts = scipipe.tarball.products
  def retries         = 3
  def extraDockerTags = ''

  if (dockerLatest) {
    extraDockerTags = '7-stack-lsst_distrib-o_latest o_latest'
  }

  def gitTagOnlyManifestId = null
  def manifestId           = null
  def stackResults         = null

  def run = {
    stage('generate manifest') {
      retry(retries) {
          gitTagOnlyManifestId = util.runRebuild(
          parameters: [
            PRODUCTS: products,
            REFS: sourceGitRefs,
            BUILD_DOCS: false,
            PREP_ONLY: true,
          ],
        )
      } // retry
    } // stage

    stage('git tag eups products') {
      retry(retries) {
        util.nodeWrap('docker') {
          util.githubTagRelease(
            options: [
              '--dry-run': false,
              '--org': scipipe.release_tag_org,
              '--manifest': gitTagOnlyManifestId,
              '--manifest-only': true,
              '--ignore-git-message': true,
              '--ignore-git-tagger': true,
            ],
            args: [gitTag],
          )
        } // util.nodeWrap
      } // retry
    } // stage

    // add aux repo tags *after* tagging eups product repos so as to avoid a
    // trainwreck if an aux repo has been pulled into the build (without
    // first being removed from the aux team).
    stage('git tag auxilliaries') {
      retry(retries) {
        util.nodeWrap('docker') {
          util.githubTagTeams(
            options: [
              '--dry-run': false,
              '--org': scipipe.release_tag_org,
              '--tag': gitTag,
            ],
          )
        } // util.nodeWrap
      } // retry
    } // stage

    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            REFS: buildGitTags,
            PRODUCTS: products,
            BUILD_DOCS: true,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      def pub = [:]

      [eupsTag, 'o_latest'].each { tagName ->
        pub[tagName] = {
          retry(retries) {
            util.runPublish(
              parameters: [
                EUPSPKG_SOURCE: eupspkgSource,
                MANIFEST_ID: manifestId,
                EUPS_TAG: tagName,
                PRODUCTS: products,
              ],
            )
          } // retry
        } // pub
      } // each

      parallel pub
    } // stage

    util.waitForS3()

    stage('build eups tarballs') {
      util.buildTarballMatrix(
        tarballConfigs: scipipe.tarball.build_config,
        parameters: [
          PRODUCTS: tarballProducts,
          EUPS_TAG: eupsTag,
          SMOKE: true,
          RUN_SCONS_CHECK: true,
          PUBLISH: true,
        ],
        retries: retries,
      )
    } // stage

    util.waitForS3()

    stage('build stack image') {
      retry(retries) {
        stackResults = util.runBuildStack(
          parameters: [
            PRODUCTS: tarballProducts,
            EUPS_TAG: eupsTag,
            DOCKER_TAGS: extraDockerTags,
            MANIFEST_ID: manifestId,
            LSST_COMPILER: lsstswConfig.compiler,
          ],
        )
      } // retry
    } // stage

    def triggerMe = [:]

    triggerMe['build Science Platform Notebook Aspect Lab image'] = {
      retry(retries) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/infra/build-sciplatlab',
          parameters: [
            string(name: 'TAG', value: eupsTag),
            booleanParam(name: 'NO_PUSH', value: false),
            booleanParam(name: 'JLBLEED', value: false),
            string(
              name: 'IMAGE_NAME',
              value: scipipe.release.step.build_sciplatlab.image_name,
            ),
            // BASE_IMAGE is the registry repo name *only* without a tag
            string(
              name: 'BASE_IMAGE',
              value: stackResults.docker_registry.repo,
            ),
          ],
          wait: false,
        )
      } // retry
    }


    triggerMe['build Science Platform Notebook Aspect Lab image (bleed)'] = {
      retry(retries) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/infra/build-sciplatlab',
          parameters: [
            string(name: 'TAG', value: eupsTag),
            booleanParam(name: 'NO_PUSH', value: false),
            booleanParam(name: 'JLBLEED', value: true),
            string(
              name: 'IMAGE_NAME',
              value: scipipe.release.step.build_sciplatlab.image_name,
            ),
            // BASE_IMAGE is the registry repo name *only* without a tag
            string(
              name: 'BASE_IMAGE',
              value: stackResults.docker_registry.repo,
            ),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['validate_drp'] = {
      retry(1) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/validate_drp',
          parameters: [
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
            booleanParam(
              name: 'NO_PUSH',
              value: scipipe.release.step.validate_drp.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: false),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['ap_verify'] = {
      retry(retries) {
        build(
          job: 'scipipe/ap_verify',
          parameters: [
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
            booleanParam(
              name: 'NO_PUSH',
              value: scipipe.release.step.ap_verify.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: false),
          ],
          wait: false,
        )
      } // retry
    }

    stage('triggered jobs') {
      parallel triggerMe
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
          build_git_tags:   buildGitTags ?: null,
          eups_tag:         eupsTag ?: null,
          final_release:    finalRelease ?: null,
          git_tag:          gitTag ?: null,
          manifest_id:      manifestId ?: null,
          products:         products ?: null,
          tarball_products: tarballProducts ?: null,
        ])

        archiveArtifacts([
          artifacts: resultsFile,
          fingerprint: true
        ])
      }
    } // stage
  } // try
} // notify.wrap
