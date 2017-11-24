# gcp_app

# Run
mvn compile exec:java -Dexec.mainClass=org.apache.beam.gcp.JoinFile -Dexec.args="--runner=DataflowRunner --gcpTempLocation=gs://bespin-bigtable-apachebeam/temp --output=gs://bespin-bigtable-apachebeam/temp/mobile" -Pdataflow-runner -Dbigtable.project=vdspinnaker -Dbigtable.instance=bigtable-instance

