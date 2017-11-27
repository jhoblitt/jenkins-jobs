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
  }
}

notify.wrap {
  def image = null
  def hub_repo = 'lsstsqre/nginx-ssl-proxy'

  node('docker') {
    stage('checkout') {
      git([
        url: 'https://github.com/lsst-sqre/nginx-ssl-proxy',
        branch: 'master'
      ])
    }

    stage('build') {
      image = docker.build("${hub_repo}", '--no-cache --pull=true .')
    }

    if (params.PUSH) {
      stage('push') {
        docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
          image.push('latest')
        }
      }
    }
  }
} // notify.wrap
