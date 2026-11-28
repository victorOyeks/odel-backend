package com.odel.payment.service;

import com.odel.payment.client.PeerClient;
import com.odel.payment.config.PaymentProperties;
import com.odel.payment.dto.PeerDtos.*;
import com.odel.payment.entity.Fee;
import com.odel.payment.entity.Payment;
import com.odel.payment.entity.Refund;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Receipt PDFs (P-10) and the payment emails: receipt (PDF attached), paid twice (E-07), refund sent, reversed (E-08). */
@Service
public class ReceiptService {
    private final PeerClient peers;
    private final PaymentProperties props;

    public ReceiptService(PeerClient peers, PaymentProperties props) {
        this.peers = peers;
        this.props = props;
    }

    /** A short code printed on the receipt, so the office can tell a real one from an edited copy. */
    static String checkCode(Payment p) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((p.getReference() + "|" + p.getId() + "|" + p.getAmountKobo())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 2).toUpperCase();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] pdf(Payment p, Fee fee, Student st) {
        TenantView t = peers.tenant();
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Receipt no.", p.getReference());
        rows.put("Student", st == null ? p.getStudentName() : st.fullName());
        rows.put("Matric no.", p.getMatricNumber());
        if (st != null && st.programme() != null) rows.put("Programme", st.programme().name() + " · " + st.levelLabel());
        rows.put("Session", fee.getSessionName());
        rows.put("Semester", Fee.semesterName(fee.getSemesterNumber()));
        rows.put("Fee", "Platform fee");
        long charge = GatewayCharge.studentCharge(p.getAmountKobo(), p.getChargedKobo());
        if (charge > 0) {
            rows.put("Platform fee", Money.ngn(p.getAmountKobo()));
            rows.put("Paystack charge", Money.ngn(charge));
            rows.put("Total paid", Money.ngn(p.getChargedKobo()));
        } else {
            rows.put("Amount", Money.ngn(p.getAmountKobo()));
        }
        rows.put("Paid on", Labels.dayTime(p.getPaidAt()));
        rows.put("Method", Labels.method(p));
        rows.put("Paystack reference", p.getGatewayTransactionId() == null ? p.getReference() : p.getGatewayTransactionId());
        rows.put("Status", Labels.paymentStatus(p.getStatus()));
        String footer = "Check code " + checkCode(p) + ". Paid through Paystack to " + t.name() + ". This receipt is valid without a signature.";
        return ReceiptPdf.render(new ReceiptPdf.Data(t.name(), t.shortName(), t.primaryColor(), peers.crestPng(t),
                "Platform fee receipt · " + Money.ngn(p.getAmountKobo()), rows, footer));
    }

    public static String fileName(Payment p) {
        return "receipt-" + p.getReference() + ".pdf";
    }

    private String paymentsLink() {
        String portal = peers.tenant().portalUrl();
        return (portal == null ? "" : portal) + "/payments";
    }

    private Student student(Payment p) {
        try {
            return peers.student(p.getStudentId());
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> base(Payment p, Student st) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("firstName", st == null || st.firstName() == null ? p.getStudentName() : st.firstName());
        v.put("feeLabel", p.getFeeLabel());
        v.put("amount", Money.label(p.getAmountKobo()));
        v.put("reference", p.getReference());
        v.put("paidAt", Labels.dayTime(p.getPaidAt()));
        v.put("method", Labels.method(p));
        v.put("paymentsLink", paymentsLink());
        return v;
    }

    private void send(Payment p, Student st, String template, Map<String, Object> vars, List<Attachment> attachments) {
        String email = st == null ? null : st.email();
        if (email == null && p.getUserId() == null) return;
        peers.send(new Message(p.getUserId(), email, template, vars, "/payments", peers.look(), attachments));
    }

    public void receipt(Payment p, Fee fee) {
        Student st = student(p);
        Map<String, Object> v = base(p, st);
        v.put("gatewayReference", p.getGatewayTransactionId() == null ? p.getReference() : p.getGatewayTransactionId());
        byte[] pdf = pdf(p, fee, st);
        send(p, st, "payment.receipt", v, List.of(new Attachment(fileName(p), "application/pdf", Base64.getEncoder().encodeToString(pdf))));
    }

    public void duplicate(Payment p, Payment kept, Refund r) {
        Student st = student(p);
        Map<String, Object> v = base(p, st);
        v.put("keptReference", kept == null ? "" : kept.getReference());
        v.put("refundTo", Views.refundTo(p));
        v.put("refundReference", r.getReference());
        v.put("expectedBy", Labels.DAY.format(r.getExpectedBy().atStartOfDay(Labels.LAGOS)));
        send(p, st, "payment.duplicate_refund", v, null);
    }

    public void refunded(Payment p, Refund r, boolean feeRefunded) {
        Student st = student(p);
        Map<String, Object> v = base(p, st);
        v.put("refundTo", Views.refundTo(p));
        v.put("refundReference", r == null ? "" : r.getReference());
        v.put("feeNote", feeRefunded ? "Your platform fee for " + p.getFeeLabel() + " is no longer paid: pay again to register and sit exams."
                : "Your platform fee for " + p.getFeeLabel() + " stays paid.");
        send(p, st, "payment.refunded", v, null);
    }

    public void reversed(Payment p) {
        Student st = student(p);
        Map<String, Object> v = base(p, st);
        String phone = props.officePhone();
        v.put("officePhone", phone == null || phone.isBlank() ? "" : " on " + phone);
        send(p, st, "payment.reversed", v, null);
    }
}
