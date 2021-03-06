package com.aestasit.puppet.integration.tests

import static org.junit.Assert.*

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

import org.junit.AfterClass
import org.junit.BeforeClass

import com.aestasit.ssh.SshOptions
import com.aestasit.ssh.dsl.CommandOutput
import com.aestasit.ssh.dsl.SshDslEngine
import com.aestasit.ssh.log.SysOutLogger

import org.junit.rules.TestRule
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

class BasePuppetIntegrationTest {

  def static Random rng = new Random()

  def static String host
  def static String keyFile
  def static String user
  def static String modulePath

  def static SshDslEngine ssh
  def static SshOptions sshOptions

  @BeforeClass
  def static void setUp() {
    readSystemProperties()
    setSslContext()
    createSshEngine()
  }

  @AfterClass
  def static void tearDown() {
  }

  def static void readSystemProperties() {
    host = System.getProperty("puppet.integration.tests.host")
    user  = System.getProperty("puppet.integration.tests.user") ?: 'root'
    keyFile  = System.getProperty("puppet.integration.tests.keyFile")
    modulePath  = System.getProperty("puppet.integration.tests.modulePath") ?: '/root/nc_puppet_modules/imported-modules:/root/nc_puppet_modules/custom-modules'
  }

  def static void createSshEngine() {
    sshOptions = new SshOptions()
    sshOptions.with {
      logger = new SysOutLogger()
      defaultHost = host
      defaultUser = user
      defaultKeyFile = new File(keyFile)
      reuseConnection = true
      trustUnknownHosts = true
      execOptions.with { showOutput = true }
      scpOptions.with { verbose = true }
    }
    ssh = new SshDslEngine(sshOptions)
  }

  def static void setSslContext() {

    def nullTrustManager = [
      checkClientTrusted: { chain, authType ->  },
      checkServerTrusted: { chain, authType ->  },
      getAcceptedIssuers: { null }
    ]

    def nullHostnameVerifier = [
      verify: { hostname, session -> true }
    ]

    SSLContext sc = SSLContext.getInstance("SSL")
    sc.init(null, [
      nullTrustManager as X509TrustManager] as TrustManager[], null)
    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory())
    HttpsURLConnection.setDefaultHostnameVerifier(nullHostnameVerifier as HostnameVerifier)
  }

  /*
   * Utility methods.
   */

  def static void download(address, downloadTo) {
    def file = new FileOutputStream(downloadTo + "/" + address.tokenize("/")[-1])
    def out = new BufferedOutputStream(file)
    out << new URL(address).openStream()
    out.close()
  }

  def static void session(Closure cl) {
    ssh.remoteSession(cl)
  }

  def static String fileText(String filePath) {
    String content = null
    session {
      content = remoteFile(filePath).text
    }
    return content
  }

  def static Integer command(String command) {
    CommandOutput result = null
    session {
      result = exec(command: command, failOnError: false)
    }
    return result?.exitStatus
  }

  def static String commandOutput(String command, String defaultOutput = "") {
    CommandOutput result = null
    session {
      result = exec(command: command, failOnError: false)
    }
    return result?.output?.toString().trim() ?: defaultOutput
  }

  def static String apply(String content, Integer repeat = 1) {
    String applyOutput = ""
    session {

      def index = Math.abs(rng.nextInt())

      // Generate dummy manifest with specified content.
      def manifest = "\nclass test${index} {\n ${content} \n}\n\nnode default { include test${index} }"
      remoteFile("test${index}.pp").text = manifest

      println "Applying manifest: $manifest"

      repeat.times {

        // Apply manifest.
        def result = exec "puppet apply -v --modulepath=${modulePath} test${index}.pp"

        result.output.eachLine { String line ->
          if (line.toLowerCase().contains('error:')) {
            throw new IntegrationError("Puppet returned an error: " + line)
          }
          applyOutput += line + "\n"
        }

      }

    }
    return applyOutput
  }

  def static boolean fileExists(String path) {
    return command("test -f $path") == 0
  }

  def static boolean directoryExists(String path) {
    return command("test -d $path") == 0
  }

  def static Integer numberOfFiles(String path) {
    return commandOutput("find ${path} 2>/dev/null | wc -l", "-1").toInteger()
  }

  def static int permissions(String path) {
    return commandOutput("stat -c %a $path", "0").toInteger()
  }

  def static String fact(String fact) {
    return commandOutput("facter $fact", "")
  }

  def static String[] pgrep(String pattern) {
    return commandOutput("pgrep -f $pattern", "-1").split('(?smi)$')
  }

  def static boolean userExists(String user) {
    return command("id $user") == 0
  }

  def static boolean packageExists(String pkg) {
    return command("rpm -qa | grep $pkg") == 0
  }

	def static TestRule commandRule(final String comm){
		return new TestRule(){

			public Statement apply(Statement arg0, Description arg1){
				BasePuppetIntegrationTest.command(comm)
				return arg0;
			}
		}
	}

	def static TestRule printRule(final String message){
		return new TestRule(){
			
			public Statement apply(Statement arg0, Description arg1){
				println(message)
				return arg0;
			}
		}
	}

	def static uploadRule(final String fileName){
		return new TestRule(){
			public Statement apply(Statement arg0, Description arg1){
				def tmpFile = File.createTempFile("upload.", ".tmp")
				tmpFile.renameTo(fileName)
				tmpFile.deleteOnExit()
				tmpFile << Thread.currentThread().getContextClassLoader().getResourceAsStream("applications/${fileName}")
				session {
					exec 'mkdir -p /var/oracle/apps'
					scp {
						from { localFile tmpFile }
						into { remoteDir '/var/oracle/apps' }
					}
				}
				tmpFile.delete()
				return arg0;
			}
		}
		}

	}
