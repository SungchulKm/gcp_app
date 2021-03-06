/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.beam.gcp;

import com.google.bigtable.v2.Row;
import org.apache.beam.sdk.io.gcp.bigtable.BigtableIO;
import com.google.cloud.bigtable.config.BigtableOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import com.google.cloud.bigtable.beam.CloudBigtableIO;
import com.google.cloud.bigtable.beam.CloudBigtableTableConfiguration;
import java.util.Map;


/**
* Join two files and writes merged contents into Bigtable   */


public class JoinFile {

  // Files holding Device information in Google Storage.
  private static final String DEVICE_TABLE =
    "gs://bespin-bigtable-apachebeam/stage/A_*.dat";
  // A file holds contact information with 300 rows.
  private static final String CONTACT_TABLE =
    "gs://bespin-bigtable-apachebeam/stage/B.csv";

  private static final String BIGTABLE_NAME = "mobile-data";
  private static final int MAX_LINES = 300;

  // Sequence Key for contacts. Starting from 1 until MAX_LINES
  private static int sequence_key = 1;

  //  private static final byte[] CF_NAME = Bytes.toBytes("name");
  private static final byte[] CF_CONTACTS = Bytes.toBytes("contacts");
  private static final byte[] C_LASTNAME = Bytes.toBytes("lastname");
  private static final byte[] C_FIRSTNAME = Bytes.toBytes("firstname");
  private static final byte[] C_CONTACT = Bytes.toBytes("contact");
  private static final byte[] C_NICKNAME = Bytes.toBytes("nickname");

/**
 * Join two collections, using sequence of contacts.
 */
  public static String[] contact_list = new String[MAX_LINES+1];

static PCollection<Mutation> joinContacts(PCollection<String> deviceTable,
    PCollection<String> contactTable) throws Exception {

  final TupleTag<String> deviceInfoTag = new TupleTag<String>();
  final TupleTag<String> contactInfoTag = new TupleTag<String>();

  // transform both input collections to tuple collections, where the keys are sequece key
  // in both cases.
  final PCollectionView<Map<String, String>> contactView = contactTable
      .apply(ParDo.of(new CreateContactDataFn()))
      .apply(View.<String, String>asMap());

  PCollection<KV<String, String>> deviceInfo = deviceTable
      .apply(ParDo.of(new CreateDeviceDataFn()));

  // Process the CoGbkResult elements generated by the CoGroupByKey transform.
  PCollection<KV<String, String>> finalResultCollection =
    deviceInfo.apply("Process", ParDo.of(
      new DoFn<KV<String, String>, KV<String, String>>() {
        @ProcessElement
        public void processElement(ProcessContext c) {

          KV<String, String> e = c.element();
          Map<String, String> contactMap = c.sideInput(contactView);
          String contact_seq = e.getKey();
          String device = e.getValue();
          String contact = contactMap.get(contact_seq);

          c.output(KV.of(device + "_" + contact_seq, contact ));
        }
    }).withSideInputs(contactView));

  // write to GCS

  PCollection<Mutation> formattedResults = finalResultCollection
      .apply("Format", ParDo.of(new DoFn<KV<String, String>, Mutation>() {
        @ProcessElement
        public void processElement(ProcessContext c) {

          KV<String, String> e = c.element();
          String[] nameList = e.getValue().split(",");

          byte[] key   = e.getKey().getBytes();
          byte[] lastname  = Bytes.toBytes(nameList[0]);
          byte[] firstname = Bytes.toBytes(nameList[1]);
          byte[] contact   = Bytes.toBytes(nameList[2]);
          byte[] nickname  = Bytes.toBytes(nameList[3]);

          c.output(new Put(key).addColumn(CF_CONTACTS, C_LASTNAME, lastname));
          c.output(new Put(key).addColumn(CF_CONTACTS, C_FIRSTNAME, firstname));
          c.output(new Put(key).addColumn(CF_CONTACTS, C_CONTACT, contact));
          c.output(new Put(key).addColumn(CF_CONTACTS, C_NICKNAME, nickname));
        } 
      }));

  return formattedResults;

}

static class CreateDeviceDataFn extends DoFn<String, KV<String, String>> {
  @ProcessElement
  public void processElement(ProcessContext c) {
    String devicename = c.element();
    String seq; 

    for (int i = 1; i <= MAX_LINES; i++) {
          seq = String.valueOf(i);
          c.output(KV.of(seq, devicename));
    }

  }
}


static class CreateContactDataFn extends DoFn<String, KV<String, String>> {
  @ProcessElement
  public void processElement(ProcessContext c) {
    String line = c.element();

    contact_list[sequence_key] = line;

    if ( contact_list[sequence_key] == null )
      System.out.println("Null : " + line );

    c.output(KV.of(String.valueOf(sequence_key), line));
    sequence_key++;
  }
}


private interface Options extends PipelineOptions {
  @Description("Path of the file to write to")
  @Validation.Required
  String getOutput();
  void setOutput(String value);
}

public static void main(String[] args) throws Exception {

  Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
  Pipeline p = Pipeline.create(options);

  String projectId = System.getProperty("bigtable.project");
  String instanceId = System.getProperty("bigtable.instance");


  PCollection<String> deviceTable =
       p.apply("ReadLines", TextIO.read().from(DEVICE_TABLE));

  PCollection<String> contactTable =
       p.apply("ReadLines", TextIO.read().from(CONTACT_TABLE));


  PCollection<Mutation> formattedResults = joinContacts(deviceTable, contactTable);

  CloudBigtableTableConfiguration config =
        new CloudBigtableTableConfiguration.Builder()
        .withProjectId(projectId)
        .withInstanceId(instanceId)
        .withTableId(BIGTABLE_NAME)
        .build();

  formattedResults.apply("write",
     CloudBigtableIO.writeToTable(config));

  p.run().waitUntilFinish();
}

}
