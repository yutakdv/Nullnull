import * as cdk from "aws-cdk-lib";
import { createAccessLogsStack } from "./access-logs";

const app = new cdk.App();
const account = app.node.tryGetContext("account");
if (typeof account !== "string" || !/^\d{12}$/.test(account)) {
  throw new Error("Explicit account required");
}
createAccessLogsStack(app, account);
