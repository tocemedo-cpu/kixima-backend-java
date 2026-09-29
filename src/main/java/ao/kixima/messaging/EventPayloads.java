package ao.kixima.messaging;

import ao.kixima.catalog.Product;
import ao.kixima.company.Company;
import ao.kixima.invoice.Invoice;
import ao.kixima.payment.Payment;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha {@code eventBus.payloads} — o formato canónico consumido pelo
 * kixima-integration-service. Construídos DENTRO da transação de negócio
 * (as relações lazy ainda estão à mão); só o envio é adiado.
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    /** `const num = (d) => Number(d ?? 0)` do eventBus.js: nos eventos os valores são NÚMEROS (não o texto Decimal das respostas HTTP). */
    private static double num(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    private static Map<String, Object> empresa(Company c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taxId", c == null || c.getTaxId() == null ? "" : c.getTaxId());
        m.put("name", c == null || c.getName() == null ? "" : c.getName());
        return m;
    }

    private static String sku(Product p) {
        if (p == null) return "";
        if (p.getSku() != null && !p.getSku().isBlank()) return p.getSku();
        return p.getManufacturerCode() == null ? "" : p.getManufacturerCode();
    }

    private static List<Map<String, Object>> linhas(PurchaseOrder po) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PurchaseOrderItem it : po.getItems()) {
            Product p = it.getProduct();
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("sku", sku(p));
            l.put("description", p == null || p.getName() == null ? "Item" : p.getName());
            l.put("quantity", it.getQuantity());
            l.put("unitPrice", num(it.getUnitPrice()));
            l.put("lineTotal", num(it.getLineTotal()));
            lines.add(l);
        }
        return lines;
    }

    /** `purchase_order.approval_requested` — a PO ERP-managed pede a decisão ao ERP do comprador. */
    public static Map<String, Object> purchaseOrderApprovalRequested(PurchaseOrder po, Instant requestedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poId", po.getId());
        m.put("reference", po.getReference());
        m.put("buyer", empresa(po.getBuyerCompany()));
        m.put("supplier", empresa(po.getSupplierCompany()));
        m.put("currency", po.getCurrency());
        m.put("totalAmount", num(po.getTotalAmount()));
        m.put("lines", linhas(po));
        m.put("requestedAt", (requestedAt == null ? Instant.now() : requestedAt).toString());
        return m;
    }

    public static Map<String, Object> purchaseOrderApproved(PurchaseOrder po, Instant approvedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("poId", po.getId());
        m.put("reference", po.getReference());
        m.put("buyer", empresa(po.getBuyerCompany()));
        m.put("supplier", empresa(po.getSupplierCompany()));
        m.put("currency", po.getCurrency());
        m.put("totalAmount", num(po.getTotalAmount()));
        m.put("lines", linhas(po));
        m.put("approvedAt", (approvedAt == null ? Instant.now() : approvedAt).toString());
        return m;
    }

    public static Map<String, Object> invoiceIssued(Invoice invoice, PurchaseOrder po) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceId", invoice.getId());
        m.put("reference", invoice.getReference());
        m.put("poReference", po == null ? null : po.getReference());
        m.put("supplier", empresa(po == null ? null : po.getSupplierCompany()));
        m.put("currency", invoice.getCurrency());
        m.put("amount", num(invoice.getAmount()));
        Instant issued = invoice.getIssuedAt() != null ? invoice.getIssuedAt()
                : invoice.getCreatedAt() != null ? invoice.getCreatedAt() : Instant.now();
        m.put("issuedAt", issued.toString());
        m.put("dueAt", (invoice.getDueAt() == null ? Instant.now() : invoice.getDueAt()).toString());
        return m;
    }

    public static Map<String, Object> paymentCompleted(Payment payment, Invoice invoice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paymentId", payment.getId());
        m.put("invoiceReference", invoice == null || invoice.getReference() == null ? "" : invoice.getReference());
        m.put("amount", num(payment.getAmount()));
        m.put("currency", payment.getCurrency());
        m.put("paidAt", (payment.getProcessedAt() == null ? Instant.now() : payment.getProcessedAt()).toString());
        m.put("method", "KIXIMA");
        return m;
    }

    public static Map<String, Object> goodsReceived(PurchaseOrder po, Instant receivedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goodsReceiptId", "gr:" + po.getId());
        m.put("poReference", po.getReference());
        m.put("receivedAt", (receivedAt == null ? Instant.now() : receivedAt).toString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PurchaseOrderItem it : po.getItems()) {
            Product p = it.getProduct();
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("sku", p == null || p.getSku() == null ? "" : p.getSku());
            l.put("description", p == null || p.getName() == null ? "Item" : p.getName());
            l.put("quantityReceived", it.getQuantity());
            lines.add(l);
        }
        m.put("lines", lines);
        return m;
    }
}
