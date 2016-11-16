Start sbt like this:

    sbt -mem2048
    

To run test against all versions of scala:

    sbt -mem2048 +test
    
to publish crossScala-version to sonatype:

    sbt -mem2048 +publish-signed
