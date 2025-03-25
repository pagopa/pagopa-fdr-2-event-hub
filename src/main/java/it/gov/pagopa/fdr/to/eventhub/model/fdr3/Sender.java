package it.gov.pagopa.fdr.to.eventhub.model.fdr3;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
public class Sender {

  private SenderTypeEnum type;
  private String id;
  private String pspId;
  private String pspName;
  private String pspBrokerId;
  private String channelId;
  private String password;
}