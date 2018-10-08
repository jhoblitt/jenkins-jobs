import util.Plumber

def p = new Plumber(name: 'scipipe/ap_verify', dsl: this)
p.pipeline().with {
  description('Execute ap_verify.')

  parameters {
    stringParam('DOCKER_IMAGE', null, 'Explicit name of release docker image including tag.')
    booleanParam('WIPEOUT', false, 'Completely wipe out workspace(s) before starting build.')
  }
}