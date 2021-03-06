package pipelines

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

import java.text.SimpleDateFormat

// Author: kennethye
// Usage: Jenkins的自动Pipeline构建脚本完整流水线

def projectName = PROJECT_NAME
def gitRepo = GIT_REPO
def registryProject = PROJECT_NAME

def deployProject = PROJECT_NAME
def buildNodeLabel = BUILD_NODE_LABEL
//def deployNodeLabel = ENV == 'staging' ? STAGING_BRIDGE_NODE_LABEL : buildNodeLabel
def applicationType = APPLICATION_TYPE //dc, deploy, statefulset
def defaultBranch = BRANCH

def openshiftPortalUrl = "https://portal.openshift.net.cn:8443"
def registryUsername = REGISTRY_USER
def registryPassword = REGISTRY_PASSWORD

//环境信息
def registryAddr = ["dev"         : "docker-registry.default.svc:5000"]
def envAddr = ["dev"    : "https://portal.openshift.net.cn:8443"]
def envUser = ["dev"    : OC_DEV_USER]
def envPass = ["dev"    : OC_DEV_PASS]
def envSubDomain = ["dev"    : "apps.openshift.net.cn"]

def registryUrl = registryAddr.get(ENV)
def imagePrefix = "${registryUrl}/${registryProject}"
def images = []
def imageTag = "latest"
def tagVersionPrefix = "v${VERSION}"
def mrIId = -1
def module = ""
def loganHighPriorApp = [""]

try {
    node(buildNodeLabel) {
        //参数检查-start
        stage('verify-and-init') {
            echo "Flow: ${ENV}, Version: ${VERSION}, SkipBuild: ${SKIP_BUILD}, SkipTest: ${SKIP_TEST}, ProjectDefaultBranch: ${defaultBranch}, SubDomain: ${envSubDomain.get(ENV)}"

            //是否符合版本号规范
            if (!isValidVersion()) {
                error("版本号[${VERSION}]不符合规范")
            }

            //构建-start
            if (!Boolean.valueOf(SKIP_BUILD)) {
                stage("git-pull") {
                    gitPull(gitRepo)
                    //获取镜像tag
                    imageTag = getImageTag(tagVersionPrefix)
                }
            }
        }

        //打包UT
        stage("mvn-pkg") {
            mavenPackage()
        }

        //代码扫描(容器内跑sonar不支持)
        //if (!Boolean.valueOf(SKIP_TEST)) {
        //    stage("sonar") {
        //        sonar()
        //    }
        //}

        //发布jar
        //stage("mvn-deploy") {
        //    mavenDeploy()
        //}

        //构建docker镜像
        stage("build-images") {
			module = getArtifactIdFromPom();
			echo "正在构建${module}模块镜像"
            sh "docker build -t ${imagePrefix}/${module}:${imageTag} ."
            images.add(module)
		}

        //推送镜像
        stage("push-images") {
            registryToken = sh (
				script: "curl -u ${registryUsername}:${registryPassword} -kI \"${openshiftPortalUrl}/oauth/authorize?client_id=openshift-challenging-client&response_type=token\" | grep -oP \"access_token=\\K[^&]*\"",
				returnStdout: true
				).trim()
			sh "docker login -u ${registryUsername} -p ${registryToken} ${registryUrl}"
            sh "docker push ${imagePrefix}/${module}:${imageTag}"
            echo "推送镜像${imagePrefix}/${module}:${imageTag}成功"
		}
    
	    // 部署应用到Openshift环境
	    stage("deploy-openshift") {
            //检查镜像是否存在
            //checkSingleImageExisting("${registryUrl}", "${registryProject}", "${imagePrefix}/${module}", "${imageTag}")

            //登陆openshift， 此用户必须有发布template, 部署更新应用权限
            sh "oc login ${envAddr.get(ENV)} -u ${envUser.get(ENV)} -p ${envPass.get(ENV)} --insecure-skip-tls-verify"
		
		    // 更新template, 发布或者更新应用
		    echo "发布模块 ${module}"
            initOpenshiftModule("${envSubDomain.get(ENV)}", "${imagePrefix}/${module}", "${module}",
                    "openshift.yml", deployProject, applicationType, "${imageTag}");

		}

	
	}

} catch (err) {
currentBuild.result = 'FAILURE'
error "Error caught: $err.message"
} finally {
    //清理工作

}


def mavenPackage() {
    echo "java程序正在打包, 是否忽略测试:${SKIP_TEST}"
    if (Boolean.valueOf(SKIP_TEST)) {
        sh "mvn clean package -U -Dmaven.test.skip=true"
    } else {
        sh "mvn clean package -U"
    }
}

def mavenDeploy() {
    echo "正在推送jar包, 是否忽略测试:${SKIP_TEST}, jar包版本为: ${VERSION}"
    sh "mvn deploy -Dmaven.test.skip=true"
}

def sonar() {
    echo "正在通过sonar进行代码静态检查,如果忽略测试,则不进行检查"
    sh "mvn sonar:sonar"
}

def gitPull(String gitRepo) {
    if (ENV == "release" || ENV == "hotfix/patch") {
        echo "使用master分支, 拉取origin ${BRANCH}的代码"
        git branch: "master", url: "${gitRepo}"
        sh "git pull origin ${BRANCH}"
    } else {
        echo "使用${BRANCH}分支: 拉取origin ${BRANCH}的代码"
        git branch: "${BRANCH}", url: "${gitRepo}"
    }
}

def getImageTag(tagVersionPrefix) {
    //测试环境使用时间戳作为镜像tag, 用于提测的版本控制
    if (ENV == "test" && !Boolean.valueOf(SKIP_BUILD)) {
        return getTagFromPom()
    }
    if (VERSION.contains("SNAPSHOT")) {
        return "latest"
    } else {
        String tagVersion = tagVersionPrefix;
		return tagVersion
    }
}

def getTagFromPom() {
    return "v${readMavenPom().getVersion().replaceAll('-SNAPSHOT', '')}-${BUILD_ID}"
}

def getArtifactIdFromPom() {
    return "${readMavenPom().getArtifactId()}"
}

def getFeatureStagingTag() {
    return "${readMavenPom().getVersion().replaceAll('-SNAPSHOT', '')}-${BRANCH.replaceAll('/', '-')}-${BUILD_ID}"
}

def getGitlabTagVersion(String tagVersionPrefix) {
    String tagVersion = tagVersionPrefix;
    if (isHotfixBranch()) {
        tagVersion += ".HOTFIX"
    } else if (isPatchBranch()) {
        tagVersion += ".PATCH"
    } else {
        tagVersion += ".RELEASE"
    }
    return tagVersion
}

def isValidVersion() {
    return VERSION == 'SNAPSHOT' || (VERSION =~ /^\d+\.\d+\.\d+(-(RC|rc)\d*){0,1}$/).matches()
}

//ENV equals release and version likes 0.2.0
def isOfficialRelease() {
    def matcher = (VERSION =~ /^\d+\.\d+\.\d+$/)
    return matcher.matches() && (ENV == "release" || ENV == "hotfix/patch")
}
//ENV equals release and version likes 0.2.0-RC1
def isRCRelease() {
    def matcher = (VERSION =~ /^\d+\.\d+\.\d+-(RC|rc)\d*$/)
    return matcher.matches() && (ENV == "release" || ENV == "hotfix/patch")
}

/**
 * Feature branch starts with 'feature-'
 * @param branch
 * @return
 */
def isFeature(def branch) {
    return (branch =~ /^feature-.+/).matches()
}

//获取最后一位版本号
def getIncrementalVersion() {
    def matches = VERSION =~ /^\d+\.\d+\.(\d+)(-(RC|rc)\d*){0,1}$/
    return matches.matches() ? Integer.valueOf(matches.group(1)) : -1
}

//Hotfix流程: hotfix分支, 版本号最后一位为偶数
def isInvalidHotfixVersion() {
    def incrementalVersion = getIncrementalVersion()
    return incrementalVersion != 0 && incrementalVersion % 2 == 0
}
//Hotfix流程: patch分支, 版本号最后一位为奇数
def isInvalidPatchVersion() {
    def incrementalVersion = getIncrementalVersion()
    return incrementalVersion % 2 == 1
}
//hotfix分支: 分支名'hotfix-'开头
def isHotfixBranch() {
    return (BRANCH =~ /^hotfix-.+/).matches()
}
//patch分支: 分支名'patch-'开头
def isPatchBranch() {
    return (BRANCH =~ /^patch-.+/).matches()
}

def initOpenshiftModule(String subDomain, String imageName, String moduleName, String moduleTemplate,
                        String projectToDeploy, String applicationType, String imageTag) {
    echo "正在初始化模块 ${moduleName}"
    sh "oc project ${projectToDeploy}"
    //开发环境的project后缀为'-dev'
    //String projectToDeploy = ENV == 'dev' ? project + "-dev" : project
    try {
        sh "oc delete -f ${moduleTemplate} -n ${projectToDeploy}"
    } catch (err) {
        echo "Template ${moduleTemplate}不存在，但不影响构建"
    }
    sh "oc create -f ${moduleTemplate} -n ${projectToDeploy}"

    def replicas =  1

    //检查dc是否存在, 如果不存在就创建
    boolean existing = false
    try {
        sh "oc get ${applicationType} ${moduleName} -n ${projectToDeploy}"
        existing = true
    } catch (err) {
        echo "deploymentconfig ${moduleName} 不存在, 需要进行初始化"
    }
    if (Boolean.valueOf(APPLICATION_INIT) || !existing) {
        try {
            sh "oc process -f ${moduleTemplate} -p ENV=${ENV} | oc delete -f - -n ${projectToDeploy}"
        } catch (err) {
            echo "Template ${moduleTemplate}对应的老对象不存在，但不影响构建"
        }
        sh "sleep 10s"
        sh "oc process -f ${moduleTemplate} -p ENV=${ENV} -p IMAGE=${imageName} -p VERSION=${imageTag} -p REPLICAS=${replicas} " +
                "-p BUILD_TIME=${currentBuild.startTimeInMillis} " +
                "-p CPU_REQUEST=100m " +
                "-p CPU_LIMIT=1000m " +
                "-p MEM_REQUEST=512Mi " +
                "-p MEM_LIMIT=2048Mi " +
                "-p SUB_DOMAIN=${subDomain} | oc create -f - -n ${projectToDeploy}"
    } else {
        try {
            sh "oc process -f ${moduleTemplate} -p ENV=${ENV} -p IMAGE=${imageName} -p VERSION=${imageTag} -p REPLICAS=${replicas} " +
                    "-p BUILD_TIME=${currentBuild.startTimeInMillis} " +
                    "-p CPU_REQUEST=100m " +
                    "-p CPU_LIMIT=1000m " +
                    "-p MEM_REQUEST=512Mi " +
                    "-p MEM_LIMIT=2048Mi " +
                    "-p SUB_DOMAIN=${subDomain} | oc create -f - -n ${projectToDeploy}"
        } catch (err) {
            echo "某些resource不支持replace, 比如Service的clusterIP不可变. 不影响"
        }

    }
}

//检查镜像是否存在
def checkSingleImageExisting(def registryUrl, def registryProject, def imageName, def tag) {
    def response = httpRequest(
            consoleLogResponseBody: true,
            httpMode: 'GET',
            url: "http://${registryUrl}/api/repositories/${registryProject}/${imageName}/tags/${tag}", // check test registry only!
            validResponseCodes: '200:404',
            contentType: 'APPLICATION_JSON',
            acceptType: 'APPLICATION_JSON'
    )
    if (response.getStatus() == 404) {
        return false
    } else {
        return true
    }
}

def checkImageExisting(def registryUrl, def registryProject, def moduleMap) {
    if(MODULE == 'all') {
        moduleMap.each { module, imageName ->
            if(!checkSingleImageExisting(registryUrl, registryProject, imageName, TAG)) {
                error "${imageName}的${tag}镜像不存在"
                return false
            }
        }
        return true
    } else {
        return checkSingleImageExisting(registryUrl, registryProject, moduleMap.get(MODULE), TAG)
    }
}


