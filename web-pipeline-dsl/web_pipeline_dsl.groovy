//#######################################DOCKER IMAGE INFRASTRUCTURE#######################################
def currentDockerImages = ['linkchecker','gh-pages', 'pac']

//Convention: all our docker image repos are prefixed with "docker-"
def githubUrl = 'https://github.com/Praqma/docker-'
def branchName = "master" //"\${BRANCH}"
def releasePraqmaCredentials = '100247a2-70f4-4a4e-a9f6-266d139da9db'
def dockerHostLabel = 'GiJeLiSlave'

currentDockerImages.each { image ->
  def cloneUrl = "${githubUrl}"+"${image}"
  
  //Verification job bame
  def verifyName = "Web_Docker_"+"${image}"+"-verify"
  
  //Publish job name
  def publishName = "Web_Docker_"+"${image}"+"-publish"
  
  //Docker repo name
  def dRepoName = "praqma/${image}"
  
  job(verifyName) {
	label(dockerHostLabel)
    
    triggers {
      githubPush()
    }
    
    scm {
      git {
        
        remote {
          url(cloneUrl)
          credentials(releasePraqmaCredentials)
        }
        
        branch(branchName)
        
        configure {
          node ->
          node / 'extensions' << 'hudson.plugins.git.extensions.impl.CleanBeforeCheckout' {}
        }
      }
    }
    
    steps {
      shell("docker build -t praqma/${image}:snapshot .") 
      shell('./test.sh')
	  shell("docker rmi praqma/${image}:snapshot")		            
    }  
    
    publishers {
      buildPipelineTrigger(publishName) {
        parameters{
          gitRevision(false)
        }
      }
	  mailer('', false, false)            
    }
  }
  
  //Publish jobs
  job(publishName) {
    label(dockerHostLabel)    
    scm {
      git {
        
        remote {
          url(cloneUrl)
          credentials(releasePraqmaCredentials)
        }
        
        branch(branchName)
          configure {
            node ->
            node / 'extensions' << 'hudson.plugins.git.extensions.impl.CleanBeforeCheckout' {}
          }
      }
    }
    
    steps {
      dockerBuildAndPublish {
        repositoryName(dRepoName)
        tag('1.${BUILD_NUMBER}')
        registryCredentials('docker-hub-crendential')
        dockerHostURI('unix:///var/run/docker.sock')
        forcePull(false)
        createFingerprints(false)
        skipDecorate()
      }      
    }
    
    publishers {
      git {
        pushOnlyIfSuccess()
        branch('origin', branchName)
        tag('origin', '1.${BUILD_NUMBER}') {
          message('Tagged with 1.${BUILD_NUMBER} using Jenkins')
          create()
        }
      }
      mailer('', false, false)      
    }
    
  }
}
//#########################################################################################################

//##########################################WEBSITE CONFIGURATION##########################################
def readyBranch = 'origin/ready/**'

//List of websites we need to create a pipeline for
def websites = [
  'http://www.praqma.com':'https://github.com/Praqma/praqma.com.git',
  'http://www.josra.org':'https://github.com/josra/josra.github.io.git'
]

//Specify the full intergration branch name
def integrationBranches = [
	'http://www.praqma.com':'gh-pages',
  	'http://www.josra.org':'master'
]

//The 'verify' job is the one that has to pass the tollgate criteria. For websites this is: jekyll build
//We're enabling pretested integration for this part of the pipeline.
//TODO: Currently i have an issue with the docker image not picking up the correct locale when the slave is connected using ssh.
//Ideally this should just spawn a slave just like the rest of the jobs. Instead it just uses do
websites.each { site, weburl -> 
  job('Web_'+site.split('http://')[1] + '-verify') {
    label(dockerHostLabel)

 	triggers {
        githubPush()
    }
    
    scm {
      git {
        
        remote {
          url(weburl)
          credentials(releasePraqmaCredentials)
        }
        
        branch(readyBranch)
        
        configure {
          node ->
          node / 'extensions' << 'hudson.plugins.git.extensions.impl.CleanBeforeCheckout' {}
        }
      }
    }
    
    steps {
      shell('docker run -u jenkins --rm -v ${WORKSPACE}:/home/jenkins praqma/gh-pages jekyll build')
    }
    
    wrappers {
      pretestedIntegration("SQUASHED", integrationBranches[site], "origin")
    }
    
    publishers {
	  archiveArtifacts('_site/**')
      pretestedIntegration()
      mailer('', false, false)      
    }    
  }
  
  //TRIGGER JOBS
  job('Web_'+site.split('http://')[1] + '-trigger') {
        
    triggers {
      githubPush()
    }
    

    scm {
      git {
        
        remote {
          url(weburl)
          credentials(releasePraqmaCredentials)
        }
        
        branch(integrationBranches[site])
        
        configure {
          node ->
          node / 'extensions' << 'hudson.plugins.git.extensions.impl.CleanBeforeCheckout' {}
        }
      }
    }
    
    steps {
      downstreamParameterized {
        trigger(['Web_'+site.split('http://')[1] + '-linkcheck','Web_'+site.split('http://')[1] + '-resource-analysis']) {
          parameters{
            gitRevision(false)
          }
        }
      }
    }
  }
  
  //The linkchecker job should run the linkchecker command and produce a set of parsable report files
  job('Web_'+site.split('http://')[1] + '-linkcheck') {
    label('linkchecker')
    
    scm {      
      git {
        
        remote {
          url(weburl)
          credentials(releasePraqmaCredentials)
        }
        
        branch(integrationBranches[site])
        
        configure {
          node ->
          node / 'extensions' << 'hudson.plugins.git.extensions.impl.CleanBeforeCheckout' {}
        }
      }
    }
    
    steps {
      shell("""
set +e
linkchecker -o text -Fcsv/linkchecker.report.csv -Fhtml/linkchecker.report.html ${site}
exit 0
      """)
    }
    
    publishers {      	
      warnings(null,['LinkChecker CSV (Jekyll flavor)':'linkchecker.report.csv'])
      
      analysisCollector() {
        warnings()
      }
      
      publishHtml {
        report('.') {
          reportName('Linkchecker report')
          reportFiles('linkchecker.report.html')
          alwaysLinkToLastBuild(true)
        }
      }
      
      mailer('', false, false)      
      
    }
  }
  //The resource analysis job. TODO: Implement this
  job('Web_'+site.split('http://')[1] + '-resource-analysis') {
	label('ruby')
    scm {
      git {
        
        remote {
          url(weburl)
          credentials(releasePraqmaCredentials)
        }
        
        branch(integrationBranches[site])
        
        configure {
          node ->
          node / 'extensions' << 'hudson.plugins.git.extensions.impl.CleanBeforeCheckout' {}
        }
      }
    }
	
    steps {
      shell("""
ruby /opt/static-analysis/analyzer.rb -c /opt/static-analysis/report_duplication_junit_template.xml -u /opt/static-analysis/report_usage_analysis_junit_template.xml 
""")
    }
    publishers {      	
	  archiveXUnit {
	    jUnit {
		  pattern('report_*.xml')
		  failIfNotNew(false)
	    }
	  }      
	  mailer('', false, false)      
    }    
  }
}

//#########################################################################################################