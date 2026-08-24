node('linux') {
  stage ('Poll') {
    checkout([
      $class: 'GitSCM', branches: [[name: '*/main']], extensions: [],
      userRemoteConfigs: [[url: 'https://github.com/zopencommunity/arrowport.git']]])
  }
  stage('Build') {
    build job: 'Port-Pipeline', parameters: [
      string(name: 'PORT_GITHUB_REPO', value: 'https://github.com/zopencommunity/arrowport.git'),
      string(name: 'PORT_DESCRIPTION', value: 'Apache Arrow C++ columnar in-memory format'),
      string(name: 'BUILD_LINE', value: 'STABLE')
    ]
  }
}
