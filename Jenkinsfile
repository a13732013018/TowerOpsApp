pipeline {
  agent any
  stages {
    stage('检出代码') {
      steps {
        checkout scm
      }
    }
    stage('准备编译环境') {
      steps {
        sh 'java -version'
        sh 'chmod +x gradlew'
        // 下载 gradle-wrapper.jar
        sh '''
          mkdir -p gradle/wrapper
          if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
            curl -L https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar \
              -o gradle/wrapper/gradle-wrapper.jar
          fi
        '''
      }
    }
    stage('编译 Debug APK') {
      steps {
        sh './gradlew assembleDebug --no-daemon'
      }
    }
    stage('归档 APK') {
      steps {
        archiveArtifacts artifacts: 'app/build/outputs/apk/debug/app-debug.apk', fingerprint: true
      }
    }
  }
  post {
    success {
      echo '✅ APK 编译成功！请在「构建产物」中下载 app-debug.apk'
    }
    failure {
      echo '❌ 编译失败，请检查日志'
    }
  }
}
