package io.github.jchapuis.leases4s.example.deployer

import besom.*
import besom.api.aws.s3
import besom.api.aws.s3.BucketObject
import besom.api.aws.s3.inputs.BucketWebsiteArgs
import besom.types.Asset.FileAsset
import besom.types.Output
import besom.json.*

import java.io.File
import java.nio.file.Files

def siteBucketFor(writer: AwsAccess)(using Context): Output[s3.Bucket] =
  for {
    siteBucket <- s3.Bucket(
      "s3-website-bucket",
      s3.BucketArgs(
        bucket = Some("s3-website-bucket"),
        website = BucketWebsiteArgs(indexDocument = "index.html"),
        forceDestroy = true
      )
    )
    siteBucketName <- siteBucket.bucket
    siteBucketPublicAccessBlock <- s3.BucketPublicAccessBlock(
      s"$siteBucketName-publicaccessblock",
      s3.BucketPublicAccessBlockArgs(
        bucket = siteBucket.id,
        blockPublicPolicy = false
      )
    )

    _ <- s3.BucketPolicy(
      s"$siteBucketName-access-policy",
      s3.BucketPolicyArgs(
        bucket = siteBucket.id,
        policy = JsObject(
          "Version" -> JsString("2012-10-17"),
          "Statement" -> JsArray(
            JsObject(
              "Sid"       -> JsString("PublicReadGetObject"),
              "Effect"    -> JsString("Allow"),
              "Principal" -> JsObject("AWS" -> JsString("*")),
              "Action"    -> JsArray(JsString("s3:GetObject")),
              "Resource"  -> JsArray(JsString(s"arn:aws:s3:::$siteBucketName/*"))
            )
          )
        ).prettyPrint
      ),
      opts(dependsOn = siteBucketPublicAccessBlock)
    )
    writerArn <- writer.user.arn
    writableAccessPolicy <- s3
      .BucketPolicy(
        s"$siteBucketName-write-policy",
        s3.BucketPolicyArgs(
          bucket = siteBucket.id,
          policy = JsObject(
            "Version" -> JsString("2012-10-17"),
            "Statement" -> JsArray(
              JsObject(
                "Sid"       -> JsString("PublicWritePutObject"),
                "Effect"    -> JsString("Allow"),
                "Principal" -> JsObject("AWS" -> JsString(writerArn)),
                "Action"    -> JsArray(JsString("s3:PutObject"), JsString("s3:DeleteObject"), JsString("s3:GetObject")),
                "Resource"  -> JsArray(JsString(s"arn:aws:s3:::$siteBucketName/*"))
              )
            )
          ).prettyPrint
        )
      )
      .flatMap(_.id)

  } yield siteBucket

def uploadSiteToBucket(siteBucket: s3.Bucket, siteDirectory: File)(using Context): Output[Unit] =
  for {
    uploads <- siteDirectory
      .listFiles()
      .view
      .filter(_.isFile)
      .traverse { file =>
        NonEmptyString(file.getName) match
          case Some(name) =>
            s3.BucketObject(
              name,
              s3.BucketObjectArgs(
                bucket = siteBucket.id,                           // reference the s3.Bucket object
                source = FileAsset(file.getAbsolutePath),         // use FileAsset to point to a file
                contentType = Files.probeContentType(file.toPath) // set the MIME type of the file
              )
            )
          case None => Output.fail(new RuntimeException("Unexpected empty file name"))
      }
  } yield ()
