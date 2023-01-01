package example;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.CertificateValidation;
import software.amazon.awscdk.services.cloudfront.Behavior;
import software.amazon.awscdk.services.cloudfront.CloudFrontAllowedMethods;
import software.amazon.awscdk.services.cloudfront.CloudFrontWebDistribution;
import software.amazon.awscdk.services.cloudfront.OriginAccessIdentity;
import software.amazon.awscdk.services.cloudfront.S3OriginConfig;
import software.amazon.awscdk.services.cloudfront.SourceConfiguration;
import software.amazon.awscdk.services.cloudfront.ViewerCertificate;
import software.amazon.awscdk.services.cloudfront.ViewerCertificateOptions;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.route53.ARecord;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneProviderProps;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.amazon.awscdk.services.route53.RecordTarget;
import software.amazon.awscdk.services.route53.targets.CloudFrontTarget;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AppStack extends Stack {
    private final Project project;

    public AppStack(final Project project,
                    final Construct parent,
                    final String id) {
        this(project, parent, id, null);
    }

    public AppStack(final Project project,
                    final Construct parent,
                    final String id,
                    final StackProps props) {
        super(parent, id, props);
        this.project = project;

        IHostedZone zone = findZone();
        Certificate cert = createCertificate(zone);
        for (Website website : project.getWebsites()) {
            Bucket webBucket = createBucket(project.getName() + "-s3-web");
            createBucketDeployment(webBucket, website.getPath());
            createCloudFrontDistribution(website.getSubdomain(), cert, webBucket, zone, website.getDefaultRootObject());
            CfnOutput.Builder.create(this, "bucketName")
                            .exportName("bucketName")
                            .value(webBucket.getBucketName())
                    .build();
        }
    }

    private IHostedZone findZone() {
        return HostedZone.fromLookup(this, project.getName() + "-zone",
                HostedZoneProviderProps.builder()
                        .domainName(project.getDomainName())
                        .build());
    }

    private Certificate createCertificate(IHostedZone zone) {
        return Certificate.Builder.create(this, project.getName() + "-certificate")
                .domainName(project.getDomainName())
                .subjectAlternativeNames(List.of("*." + project.getDomainName()))
                .validation(CertificateValidation.fromDns(zone))
                .build();
    }

    private Bucket createBucket(String id) {
        return Bucket.Builder.create(this, id)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .build();
    }

    private void createBucketDeployment(Bucket bucket, String path) {
        BucketDeployment.Builder.create(this, project.getName() + "-s3-deployment")
                .sources(Collections.emptyList())
                //.sources(Collections.singletonList(Source.asset("../"+ path)))
                .destinationBucket(bucket)
                .build();
    }

    private CloudFrontWebDistribution createCloudFrontDistribution(String subdomain,
                                                                   Certificate certificate,
                                                                   Bucket bucket,
                                                                   IHostedZone zone,
                                                                   String defaultRootObject) {
        String domainName = subdomain != null ?
                subdomain + "." + project.getDomainName() : project.getDomainName();
        List<String> aliases = Stream.of(domainName, project.getDomainName()).distinct().collect(Collectors.toList());
        //aliases = Collections.emptyList();
        CloudFrontWebDistribution cloudFrontWebDistribution = CloudFrontWebDistribution.Builder.create(this,
                project.getName() + "-" + subdomain  + "-cloudfront-distribution")
                .originConfigs(Collections.singletonList(SourceConfiguration.builder()
                        .s3OriginSource(S3OriginConfig.builder()
                                .s3BucketSource(bucket)
                                .originAccessIdentity(OriginAccessIdentity.Builder.create(this, project.getName() + "-" + subdomain + "-origin-access-identity")
                                        .build())
                                .build())
                        .behaviors(Collections.singletonList(Behavior.builder()
                                .isDefaultBehavior(true)
                                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                                .allowedMethods(CloudFrontAllowedMethods.GET_HEAD_OPTIONS)
                                .build()))
                        .build()))
                .defaultRootObject(defaultRootObject)
                .viewerCertificate(ViewerCertificate.fromAcmCertificate(certificate, ViewerCertificateOptions.builder()
                        .aliases(aliases)
                        .build()))
                .build();
        for (String alias : aliases) {
            ARecord.Builder.create(this, project.getName() + "-a-record-" + "-" + alias + "-cloudfront-distribution")
                    .zone(zone)
                    .recordName(alias)
                    .target(RecordTarget.fromAlias(new CloudFrontTarget(cloudFrontWebDistribution)))
                    .build();
        }
        return cloudFrontWebDistribution;
    }
}
