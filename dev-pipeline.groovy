node('pipeline') {
    buildParam = null
    buildProject = "${SERVICE_NAME}-build"
    devProject = "${SERVICE_NAME}-dev"
    testConfigMap = "${SERVICE_NAME}-test-scripts"
    testPod = "${SERVICE_NAME}-test"
    
    stage('Get Sources') {
        echo "## Download artifact zipfile"
        sh 'curl -oartifact.zip ${ARTIFACT_URL}'
        unzip zipFile: 'artifact.zip', dir: 'artifact'

        echo "## Add ARTIFACT_URL to build-param.txt"
        sh 'echo "ARTIFACT_URL=${ARTIFACT_URL}" >> artifact/build-param.txt'

        echo "## Read build-param.txt"
        buildParam = readProperties file: 'artifact/build-param.txt'

        echo "## Get pipeline build source"
        dir('src') {
            git url: buildParam.PIPELINE_BUILD_SOURCE, branch: buildParam.PIPELINE_BUILD_BRANCH
            //sh "git checkout ${buildParam.PIPELINE_BUILD_COMMIT}"
        }
    }

    stage('Build') {
        echo "## Login to dev cluster"
        withCredentials([string(credentialsId: DEV_TOKEN_SECRET, variable: 'DEV_TOKEN')]) {
            sh "oc login $DEV_OPENSHIFT_URL " +
               "--token=$DEV_TOKEN " +
               "--certificate-authority=/run/secrets/kubernetes.io/serviceaccount/ca.crt"
        }

        echo "## Process build template to update build config:"
        sh "oc project ${buildProject}"
        sh "oc process -f src/build-template.yaml --ignore-unknown-parameters " +
           "--param-file=artifact/build-param.txt " +
           "| oc apply -f -"

        echo "## Start build:"
        sh "oc start-build ${SERVICE_NAME} --from-archive=artifact.zip -F"
    }

    stage('Deploy to dev') {
        echo "## Process deploy template to initiate deploy:"
        sh "oc project ${devProject}"
        sh "oc process -f src/deploy-template.yaml --ignore-unknown-parameters " +
           "--param-file=artifact/build-param.txt " +
           "-p ENV=dev " +
           "-p BUILD_NAMESPACE=${buildProject} " +
           "-p CPU_LIMIT=${buildParam.DEV_CPU_LIMIT} " +
           "-p CPU_REQUEST=${buildParam.DEV_CPU_REQUEST} " +
           "-p MEMORY_LIMIT=${buildParam.DEV_MEMORY_LIMIT} " +
           "-p MEMORY_REQUEST=${buildParam.DEV_MEMORY_REQUEST} " +
           "| oc apply -f -"

        echo "## Wait for deployment to complete:"
        sh "oc rollout status dc/${SERVICE_NAME} -w"
    }
    
    stage('Test in dev') {
        echo "## Reset from previous testing:"
        sh "oc delete pod ${testPod} --ignore-not-found"
        sh "oc delete configmap ${testConfigMap} --ignore-not-found"

        echo "## Create ${testConfigMap} ConfigMap"
        sh "oc create configmap ${testConfigMap} --from-file=src/test-scripts/"
        sh "oc label configmap ${testConfigMap} service=${SERVICE_NAME}"

        echo "## Process test template to initiate tests:"
        getTestStatus = "oc get pod ${testPod} -o jsonpath='{.status.phase}'"
        sh "oc process -f src/test-template.yaml --ignore-unknown-parameters " +
           "--param-file=artifact/build-param.txt " +
           "-p ENV=dev " +
           "| oc apply -f -"

        echo "## Wait for test Pod to start:"
        sh "while [[ 'Pending' = `${getTestStatus}` ]]; do sleep 10; done"

        echo "## Get test logs:"
        sh "oc logs -f ${testPod}"

        echo "## Check for test success:"
        sh "[[ 'Succeeded' = `${getTestStatus}` ]]"
    }

    stage('Record success in dev') {
        echo "## Save build parameters in ${devProject} project:"
        sh "oc delete configmap build-param --ignore-not-found"
        sh "oc create configmap build-param --from-env-file=artifact/build-param.txt"

        echo "## Save deploy template in ${devProject} project:"
        sh "oc apply -f src/deploy-template.yaml"
    }
}
