# fetchlibs-maven-plugin

A Maven plugin for downloading all specified dependencies into one folder. Then they can be added into classpath and used within your application:

    java -cp 'tinyApp.jar;./libs/*' cz.cesal.app.Start

* [Why](#why)
* [Setup](#setup)
  * [Building your application - add depends-maven-plugin]
  * [Release POM - add this plugin (fetchlibs-maven-plugin)]
* [Usage](#usage)

## Why?

It is faster to fetch only your application when you release it and not all unchanged dependencies. With fat-jar, you are always downloading hundreds of megabytes, but with this plugin, you can fetch only a few kB!

## Setup

You typically use this plugin when downloading compiled JAR from your repository into production server. After download of released JAR, plugin looks into it and fetches all specified dependencies.

### Building your application - add depends-maven-plugin

When building your application, depends-maven-plugin adds file with a list of all dependencies.

    <plugin>
        <groupId>org.apache.servicemix.tooling</groupId>
        <artifactId>depends-maven-plugin</artifactId>
    </plugin>
    
This list is included in released JAR and then used later when fetching released JAR.
For downloading your released JAR, you can use maven-dependency-plugin (install:copy).

### Release POM - add this plugin (fetchlibs-maven-plugin)

Add this snippet into your pom.xml:

    <build>
      <plugins>
        ...
        <plugin>
          <groupId>cz.cesal</groupId>
          <artifactId>fetchlibs-maven-plugin</artifactId>
          <version>1.0.0-SNAPSHOT</version>
          <configuration>
           	<source>app.jar</source>
				<target>libs</target>
				<startClassToFile>startClass.txt</startClassToFile>
          </configuration>
          <executions>
					<execution>
						<phase>install</phase>
						<goals>
							<goal>fetchlibs</goal>
						</goals>
					</execution>
				</executions>
        </plugin>
        ...
      </plugins>
    </build>

## Usage

You can build an image with the above configuration by running this command:

    mvn fetchlibs:fetchlibs
    
If you replace source parameter value with for example <source>${app.filename}</source>, you can use:

    mvn fetchlibs:fetchlibs -Dapp.filename=other-name.jar

You can parametrize other parameters as well.