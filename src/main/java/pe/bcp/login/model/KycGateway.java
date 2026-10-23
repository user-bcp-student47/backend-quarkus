// KycGateway.java
package service;

import model.KycOutcome;

public interface KycGateway {
    String createVerification(String documentType, String document);

    KycOutcome getVerification(String verificationId);

    void decide(String verificationId, String decision);
}