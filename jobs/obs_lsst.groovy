import util.Plumber

def p = new Plumber(name: 'scipipe/obs_lsst', dsl: this)
p.pipeline().with {
  description('Execute CI tests for obs_lsst.')

  parameters {
    stringParam('DOCKER_IMAGE', null, 'Explicit name of release docker image including tag.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
  }
}
