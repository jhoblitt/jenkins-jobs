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
  node('docker') {
    stage('pull') {
      docker.image('lsstsqre/centos:6-packerprep').pull()
      docker.image('lsstsqre/centos:7-packerprep').pull()
    }

    stage('build') {
      git([
        url: 'https://github.com/lsst-sqre/packer-newinstall.git',
        branch: 'master'
      ])

      def make = '''
        gem install --no-ri --no-rdoc bundler

        make
        bundle install
        bundle exec librarian-puppet install

        ./bin/packer build --only=docker-centos-6,docker-centos-7 centos_newinstall.json
      '''.stripIndent()

      rvm(make)
    }

    stage('push') {
      docker.withRegistry('https://index.docker.io/v1/', 'dockerhub-sqreadmin') {
        docker.image('lsstsqre/centos:6-newinstall').push()
        docker.image('lsstsqre/centos:7-newinstall').push()
      }
    }
  } // node
} // notify.wrap

def rvm(String commands) {
  util.shColor "bash -c 'source /etc/profile.d/rvm.sh && rvm install 2.2 > /dev/null 2>&1'"
  util.shColor "bash -c 'source /etc/profile.d/rvm.sh && rvm use 2.2 && ${commands}'"
}
