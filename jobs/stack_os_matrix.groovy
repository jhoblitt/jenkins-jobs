import util.Plumber
import org.yaml.snakeyaml.Yaml

def scipipe = new Yaml().load(readFileFromWorkspace('etc/scipipe/build_matrix.yaml'))

def p = new Plumber(name: 'stack-os-matrix', dsl: this)
p.pipeline().with {
  description('Execute a build of EUPS products using `lsstsw`.')

  parameters {
    stringParam('REFS', null, 'Whitespace delimited list of "refs" to attempt to build.  Priority is highest -> lowest from left to right.  "master" or branch from repos.yaml is implicitly appended to the right side of the list, if not specified; do not specify "master" explicitly.')
    stringParam('PRODUCTS', scipipe.canonical.products,
      'Whitespace delimited list of EUPS products to build.')
    booleanParam('BUILD_DOCS', true, 'Build pipelines.lsst.io site')
    booleanParam('PUBLISH_DOCS', true, 'Publish pipelines.lsst.io/v edition')
    stringParam('SPLENV_REF', scipipe.template.splenv_ref, 'conda env ref')
  }

  concurrentBuild(true)

  environmentVariables(
    BUILD_CONFIG: 'scipipe-lsstsw-matrix',
    WIPEOUT: false,
  )
}
