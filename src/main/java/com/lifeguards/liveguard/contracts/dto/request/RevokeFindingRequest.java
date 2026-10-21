package com.lifeguards.liveguard.contracts.dto.request;
import java.util.UUID;
import com.lifeguards.liveguard.contracts.dto.DtoValidation;
import com.lifeguards.liveguard.contracts.exception.InvalidContractException;
public record RevokeFindingRequest(UUID clientRequestId,int expectedCaseVersion,String reason){
 public RevokeFindingRequest {
  DtoValidation.requireNonNull(clientRequestId,"clientRequestId");
  reason=DtoValidation.requireBoundedText(reason,"reason",500);
  if(expectedCaseVersion<1)throw new InvalidContractException("expectedCaseVersion must be positive");
 }
}
