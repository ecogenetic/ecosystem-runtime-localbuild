FROM dockerfile/java:oracle-java8

VOLUME /tmp
ADD ecosystem-runtime-localbuild.jar /opt/ecosystem-runtime-localbuild/
EXPOSE 8080
WORKDIR /opt/ecosystem-runtime-localbuild/
CMD ["java", "-Djava.security.egd=file:/dev/./urandom", "-Xms512m", "-Xmx1g", "-jar", "ecosystem-runtime-localbuild.jar"]
