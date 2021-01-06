import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix_test.yaml'))

// note that this job *will not work* unless run-rebuild has been executed at
// least once in order to initialize the env.
def p = new Plumber(name: 'release/test-run-publish', dsl: this)
p.pipeline().with {
  description('Create and publish EUPS distrib packages. (TEST)')

  parameters {
    choiceParam('EUPSPKG_SOURCE', ['git', 'package'], 'type of "eupspkg" to create -- "git" should always be used except for a final (non-rc) release')
    stringParam('MANIFEST_ID', null, 'MANIFEST_ID/BUILD_ID/BUILD/bNNNN generated by lsst_build to generate EUPS distrib packages from. Eg. b7777')
    stringParam('EUPS_TAG', null, 'EUPS distrib tag name to publish. Eg. w_9999_52')
    stringParam('PRODUCTS', null, 'Whitespace delimited list of EUPS products to tag.')
    stringParam('TIMEOUT', '1', 'build timeout in hours')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
    // enable for debugging only
    // booleanParam('NO_PUSH', true, 'Skip s3 push.')
  }
}
