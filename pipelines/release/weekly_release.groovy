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
  util.requireParams(['YEAR', 'WEEK'])

  String year = params.YEAR.padLeft(4, "0")
  String week = params.WEEK.padLeft(2, "0")

  def products        = scipipe.canonical.products
  def tarballProducts = scipipe.tarball.products
  def retries         = 3
  def extraDockerTags = '7-stack-lsst_distrib-w_latest w_latest'

  def gitTag       = null
  def eupsTag      = null
  def manifestId   = null
  def stackResults = null

  def lsstswConfig = scipipe.canonical.lsstsw_config

  def run = {
    stage('format weekly tag') {
      gitTag  = "w.${year}.${week}"
      eupsTag = util.sanitizeEupsTag(gitTag)
      echo "generated [git] tag: ${gitTag}"
      echo "generated [eups] tag: ${eupsTag}"
    } // stage

    stage('build') {
      retry(retries) {
        manifestId = util.runRebuild(
          parameters: [
            PRODUCTS: products,
            BUILD_DOCS: true,
          ],
        )
      } // retry
    } // stage

    stage('eups publish') {
      def pub = [:]

      [eupsTag, 'w_latest'].each { tagName ->
        pub[tagName] = {
          retry(retries) {
            util.runPublish(
              parameters: [
                EUPSPKG_SOURCE: 'git',
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

    stage('git tag eups products') {
      retry(retries) {
        util.nodeWrap('docker') {
          // needs eups distrib tag to be sync'd from s3 -> k8s volume
          util.githubTagRelease(
            options: [
              '--dry-run': false,
              '--org': scipipe.release_tag_org,
              '--manifest': manifestId,
              '--eups-tag': eupsTag,
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

    triggerMe['build Science Platform Notebook Aspect Lab images'] = {
      stage('build normal Lab images') {
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
          )
        } // retry
      } // stage

      // After building the weekly, we fire off the sal-sciplat-lab jobs
      //  that depend on it.

      // The idea here is that all the ones that are links should be built
      //  sequentially, with an aggregator script used to
      //  ensure they all run on the same host so they take advantage of
      //  having a build that just needs a tag-and-push.  Those which are
      //  not all the same underlying versions can be built in parallel.
      // Unfortunately, Jenkins Groovy does not allow nesting of parallel
      //  stages within other parallel stages, so we do these all
      //  sequentially in order not to have them held up by validate_drp.

      stage('SAL-sciplat-lab linked image set for teststands') {
        retry(retries) {
          build(
            job: 'sqre/infra/aggregate-sal',
            parameters: [
              string(name: 'TAG', value: eupsTag),
              string(name: 'ENVIRONMENTS', value: 'nts tts kueyen'),
            ],
            wait: false,
          )
        } // retry
      } // stage

      stage('SAL-sciplat-lab "base" and "summit" image') {
        retry(retries) {
          build(
            job: 'sqre/infra/aggregate-sal',
            parameters: [
              string(name: 'TAG', value: eupsTag),
              string(name: 'ENVIRONMENTS', value: 'base summit'),
            ],
            wait: false,
          )
        } //retry
      } // stage
    }

    triggerMe['build Science Platform Notebook Aspect Lab image (bleed)'] = {
      retry(retries) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/infra/build-sciplatlab',
          parameters: [
            string(name: 'TAG', value: eupsTag),
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

    triggerMe['validate_drp_gen3'] = {
      retry(1) {
        // based on lsstsqre/stack image
        build(
          job: 'sqre/validate_drp_gen3',
          parameters: [
            string(name: 'DOCKER_IMAGE', value: stackResults.image),
            booleanParam(
              name: 'NO_PUSH',
              value: scipipe.release.step.validate_drp_gen3.no_push,
            ),
            booleanParam(name: 'WIPEOUT', value: false),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['doc build'] = {
      retry(retries) {
        build(
          job: 'sqre/infra/documenteer',
          parameters: [
            string(name: 'EUPS_TAG', value: eupsTag),
            string(name: 'LTD_SLUG', value: eupsTag),
            string(name: 'RELEASE_IMAGE', value: stackResults.image),
            booleanParam(
              name: 'PUBLISH',
              value: scipipe.release.step.documenteer.publish,
            ),
          ],
          wait: false,
        )
      } // retry
    }

    triggerMe['sims weekly'] = {
      retry(retries) {
        build(
          job: 'sims/weekly-release',
          parameters: [
            stringParam(name: 'YEAR', value: year),
            stringParam(name: 'WEEK', value: week),
            stringParam(name: 'LSST_DISTRIB_GIT_TAG', value: gitTag),
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
          manifest_id: manifestId ?: null,
          git_tag: gitTag ?: null,
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
