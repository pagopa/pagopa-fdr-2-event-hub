package it.gov.pagopa.fdr.to.eventhub.model.fdr3;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@Jacksonized
@NoArgsConstructor
@AllArgsConstructor
public class Sender {

  private SenderTypeEnum type;

  private String id;

  private String pspId;

  private String pspName;

  private String pspBrokerId;

  private String channelId;

  private String password;
}
