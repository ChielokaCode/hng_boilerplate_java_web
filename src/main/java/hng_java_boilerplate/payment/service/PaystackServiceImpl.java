package hng_java_boilerplate.payment.service;

import hng_java_boilerplate.payment.entity.Payment;
import hng_java_boilerplate.payment.enums.PaymentStatus;
import hng_java_boilerplate.payment.exceptions.UserNotFoundException;
import hng_java_boilerplate.payment.repositories.PaymentRepository;
import hng_java_boilerplate.payment.dtos.reqests.PaymentRequest;
import hng_java_boilerplate.payment.dtos.responses.PaymentInitializationResponse;
import hng_java_boilerplate.payment.dtos.responses.PaymentVerificationResponse;
import hng_java_boilerplate.user.entity.User;
import hng_java_boilerplate.user.service.UserService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;


@Service
public class PaystackServiceImpl implements PaystackService {

    public PaystackServiceImpl(UserService userService, PaymentRepository paymentRepository) {
        this.userService = userService;
        this.paymentRepository = paymentRepository;
    }

    private final PaymentRepository paymentRepository;
    private Logger logger = LoggerFactory.getLogger(PaystackServiceImpl.class);

    private final UserService userService;

    @Value("${paystack.secret.key}")
    private String paystackSecretKey;

    @Override
    public ResponseEntity<?> initiatePayment(PaymentRequest request) {
        User user = validateLoggedInUser();
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + paystackSecretKey);
        headers.set("Content-Type", "application/json");

        JSONObject requestPayload = new JSONObject();
        requestPayload.put("email", user.getEmail().replace("\"", "").trim());
        requestPayload.put("amount", request.getAmount() * 100);
        requestPayload.put("channels", new String[]{"card", "bank", "ussd", "qr", "bank_transfer"});

        HttpEntity<String> httpEntity = new HttpEntity<>(requestPayload.toString(), headers);
        ResponseEntity<String> response = restTemplate.exchange("https://api.paystack.co/transaction/initialize", HttpMethod.POST, httpEntity, String.class);
        System.out.println(response.getBody());
        if (response.getStatusCode() == HttpStatus.OK) {
            JSONObject jsonResponse = new JSONObject(response.getBody());
            String authorizationUrl = jsonResponse.getJSONObject("data").getString("authorization_url");
            String reference = jsonResponse.getJSONObject("data").getString("reference");
            logger.info("Authorization URL: {}", authorizationUrl);
            logger.info("Reference: {}", reference);
            Payment payment = Payment.builder().initiatedAt(LocalDateTime.now()).transactionReference(reference).amount(new BigDecimal(request.getAmount())).userEmail(user.getEmail()).build();
            var respons = paymentRepository.save(payment);
            System.out.println("pay res -- " + respons);
            PaymentInitializationResponse initializationResponse = PaymentInitializationResponse.builder().authorizationUrl(authorizationUrl).reference(reference).build();
            return ResponseEntity.ok(initializationResponse);
        } else {
            logger.error("Failed to initiate payment. Status code: {}, Response body: {}", response.getStatusCode(), response.getBody());
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        }
    }

    private User validateLoggedInUser() {
        User user = userService.getLoggedInUser();
        if (user != null) {
            return user;
        } else {
            throw new UserNotFoundException("User not authorized");
        }
    }

    @Override
    public ResponseEntity<?> verifyPayment(String reference) {
        User user = validateLoggedInUser();
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + paystackSecretKey);
        headers.set("Content-Type", "application/json");

        HttpEntity<String> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange("https://api.paystack.co/transaction/verify/" + reference, HttpMethod.GET, entity, String.class);
        validatePaymentVerificationResponse(user.getEmail(), response);
        JSONObject jsonResponse = new JSONObject(response.getBody());
        JSONObject dataObject = jsonResponse.getJSONObject("data");
        PaymentVerificationResponse verificationResponse = PaymentVerificationResponse.builder().reference(dataObject.getString("reference")).status(dataObject.getString("status")).currency(dataObject.getString("currency")).channel(dataObject.getString("channel")).paid_at(dataObject.getString("paid_at")).amount(String.valueOf(dataObject.getLong("amount"))).build();
        return ResponseEntity.ok(verificationResponse);
    }

    private void validatePaymentVerificationResponse(String email, ResponseEntity<String> response) {
        if (response.getStatusCode() == HttpStatus.OK) {
            JSONObject jsonResponse = new JSONObject(response.getBody());
            JSONObject data = jsonResponse.getJSONObject("data");
            String status = data.getString("status");
            Optional<Payment> payment = paymentRepository.findByUserEmail(email);
            if (payment.isPresent()) {
                Payment fondPayment = payment.get();
                fondPayment.setPaymentStatus(getPaymentStatus(status));
                fondPayment.setPaymentChannel(data.getString("currency"));
                fondPayment.setCompletedAt(LocalDateTime.parse(data.getString("paid_at").replace("Z", "")));
                fondPayment.setAmount(BigDecimal.valueOf(data.getLong("amount")));
                fondPayment.setCurrency(data.getString("currency"));
            }
        } else {
            logger.error("Failed to verify payment. Status code: {}, Response body: {}", response.getStatusCode(), response.getBody());
        }
    }

    private static PaymentStatus getPaymentStatus(String status) {
        PaymentStatus paymentStatus;
        switch (status) {
            case "success" -> {
                paymentStatus = PaymentStatus.PAID;
            }
            case "failed" -> paymentStatus = PaymentStatus.FAILED;
            case "processing" -> paymentStatus = PaymentStatus.PROCESSING;
            case "abandoned" -> paymentStatus = PaymentStatus.ABANDONED;
            case "reversed" -> paymentStatus = PaymentStatus.REVERSED;
            default -> paymentStatus = PaymentStatus.UNKNOWN;
        }
        return paymentStatus;
    }

}
