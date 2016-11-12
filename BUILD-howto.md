Start sbt like this:

    sbt -mem 2048
    

To run test against all versions of scala:

    sbt -mem 2048 +test
    
to publish crossScala-version to sonatype:

    sbt -mem 2048 +publish-signed