package io.github.jchapuis.leases4s.example.deployer

import besom.{Context, NonEmptyString, Output}
import besom.api.aws.iam.*

final case class AwsAccess(user: User, accessKey: AccessKey, accessKeyId: String, secretKey: String)

def awsAccessFor(userName: NonEmptyString)(using Context): Output[AwsAccess] =
  for {
    user            <- User(userName)
    accessKey       <- AccessKey("exampleAccessKey", AccessKeyArgs(user = user.name))
    accessKeyId     <- accessKey.id
    accessKeySecret <- accessKey.secret
  } yield AwsAccess(user, accessKey, accessKeyId, accessKeySecret)
