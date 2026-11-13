package com.myproject.S2dcms.Service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sendinblue.ApiException;
import sibApi.TransactionalEmailsApi;
import sibModel.*;

import java.util.Collections;

@Service
public class MailService {

    private final TransactionalEmailsApi emailApi;

    @Value("${brevo.sender.email}")
    private String senderEmail;

    @Value("${brevo.sender.name}")
    private String senderName;

    @Value("${frontend.base.url}")
    private String frontendBaseUrl;

    public MailService(TransactionalEmailsApi emailApi) {
        this.emailApi = emailApi;
    }

    // =========================
    // EMAIL VERIFICATION
    // =========================
    public void sendVerificationEmail(String toEmail, String token) throws ApiException {

        String verifyLink = frontendBaseUrl + "/verify?token=" + token;

        String htmlContent =
                "<!DOCTYPE html>" +
                        "<html>" +
                        "<head><meta charset='UTF-8'><title>Email Verification</title></head>" +
                        "<body style='margin:0;padding:0;background:#f4f6f8;'>" +
                        "  <table width='100%' cellpadding='0' cellspacing='0'>" +
                        "    <tr>" +
                        "      <td align='center' style='padding:40px 0;'>" +
                        "        <table width='600' cellpadding='0' cellspacing='0' " +
                        "               style='background:#ffffff;border-radius:8px;" +
                        "                      box-shadow:0 4px 10px rgba(0,0,0,0.08);'>" +

                        "          <tr>" +
                        "            <td align='center' style='padding:30px;'>" +
                        "              <div style='font-size:28px;font-weight:bold;color:#2563eb;'>" +
                        "                🎓 S2DCMS" +
                        "              </div>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td style='padding:0 40px 20px 40px;font-family:Arial;color:#111;'>" +
                        "              <h2>Verify your email</h2>" +
                        "              <p style='font-size:14px;line-height:1.6;color:#555;'>" +
                        "                Thank you for registering with <b>S2DCMS</b>." +
                        "                Please confirm your email address to activate your account." +
                        "              </p>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td align='center' style='padding:20px;'>" +
                        "              <a href='" + verifyLink + "' " +
                        "                 style='background:#2563eb;color:#ffffff;" +
                        "                        padding:14px 28px;text-decoration:none;" +
                        "                        font-weight:bold;border-radius:6px;" +
                        "                        display:inline-block;'>" +
                        "                Verify Email" +
                        "              </a>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td style='padding:30px;font-family:Arial;font-size:12px;" +
                        "                       color:#999;text-align:center;'>" +
                        "              If you did not create this account, you can safely ignore this email.<br/><br/>" +
                        "              © 2026 S2DCMS. All rights reserved." +
                        "            </td>" +
                        "          </tr>" +

                        "        </table>" +
                        "      </td>" +
                        "    </tr>" +
                        "  </table>" +
                        "</body>" +
                        "</html>";

        sendEmail(toEmail, "Verify your email", htmlContent);
    }

    // =========================
    // PASSWORD RESET
    // =========================
    public void sendPasswordResetEmail(String toEmail, String token) throws ApiException {

        String resetLink = frontendBaseUrl + "/reset-password?token=" + token;
        String htmlContent =
                "<!DOCTYPE html>" +
                        "<html>" +
                        "<head><meta charset='UTF-8'><title>Password Reset</title></head>" +
                        "<body style='margin:0;padding:0;background:#f4f6f8;'>" +
                        "  <table width='100%' cellpadding='0' cellspacing='0'>" +
                        "    <tr>" +
                        "      <td align='center' style='padding:40px 0;'>" +
                        "        <table width='600' cellpadding='0' cellspacing='0' " +
                        "               style='background:#ffffff;border-radius:8px;" +
                        "                      box-shadow:0 4px 10px rgba(0,0,0,0.08);'>" +

                        "          <tr>" +
                        "            <td align='center' style='padding:30px;'>" +
                        "              <div style='font-size:28px;font-weight:bold;color:#2563eb;'>" +
                        "                🎓 S2DCMS" +
                        "              </div>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td style='padding:0 40px 20px 40px;font-family:Arial;color:#111;'>" +
                        "              <h2>Password Reset</h2>" +
                        "              <p style='font-size:14px;line-height:1.6;color:#555;'>" +
                        "                We received a request to reset your password." +
                        "                Click the button below to continue." +
                        "              </p>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td align='center' style='padding:20px;'>" +
                        "              <a href='" + resetLink + "' " +
                        "                 style='background:#dc2626;color:#ffffff;" +
                        "                        padding:14px 28px;text-decoration:none;" +
                        "                        font-weight:bold;border-radius:6px;" +
                        "                        display:inline-block;'>" +
                        "                Reset Password" +
                        "              </a>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td style='padding:30px;font-family:Arial;font-size:12px;" +
                        "                       color:#999;text-align:center;'>" +
                        "              If you didn’t request this, ignore this email.<br/><br/>" +
                        "              © 2026 S2DCMS. All rights reserved." +
                        "            </td>" +
                        "          </tr>" +

                        "        </table>" +
                        "      </td>" +
                        "    </tr>" +
                        "  </table>" +
                        "</body>" +
                        "</html>";

        sendEmail(toEmail, "Reset your password", htmlContent);
    }

    public void sendDepartmentPasswordResetEmail(String toEmail, String token) throws ApiException {

        String resetLink = frontendBaseUrl + "/reset-password?token=" + token;

        String htmlContent =
                "<!DOCTYPE html>" +
                        "<html>" +
                        "<head><meta charset='UTF-8'><title>Password Reset</title></head>" +
                        "<body style='margin:0;padding:0;background:#f4f6f8;'>" +
                        "  <table width='100%' cellpadding='0' cellspacing='0'>" +
                        "    <tr>" +
                        "      <td align='center' style='padding:40px 0;'>" +
                        "        <table width='600' cellpadding='0' cellspacing='0' " +
                        "               style='background:#ffffff;border-radius:8px;" +
                        "                      box-shadow:0 4px 10px rgba(0,0,0,0.08);'>" +

                        "          <tr>" +
                        "            <td align='center' style='padding:30px;'>" +
                        "              <div style='font-size:28px;font-weight:bold;color:#2563eb;'>" +
                        "                🎓 S2DCMS" +
                        "              </div>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td style='padding:0 40px 20px 40px;font-family:Arial;color:#111;'>" +
                        "              <h2>Password Reset</h2>" +
                        "              <p style='font-size:14px;line-height:1.6;color:#555;'>" +
                        "                We received a request to reset your password." +
                        "                Click the button below to continue." +
                        "              </p>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td align='center' style='padding:20px;'>" +
                        "              <a href='" + resetLink + "' " +
                        "                 style='background:#dc2626;color:#ffffff;" +
                        "                        padding:14px 28px;text-decoration:none;" +
                        "                        font-weight:bold;border-radius:6px;" +
                        "                        display:inline-block;'>" +
                        "                Reset Password" +
                        "              </a>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td style='padding:30px;font-family:Arial;font-size:12px;" +
                        "                       color:#999;text-align:center;'>" +
                        "              If you didn't request this, ignore this email.<br/><br/>" +
                        "              © 2026 S2DCMS. All rights reserved." +
                        "            </td>" +
                        "          </tr>" +

                        "        </table>" +
                        "      </td>" +
                        "    </tr>" +
                        "  </table>" +
                        "</body>" +
                        "</html>";

        sendEmail(toEmail, "Reset your password", htmlContent);
    }

    public void sendContactEmail(String toEmail, String senderName, String senderEmail, String message) throws ApiException {
        String htmlContent =
                "<!DOCTYPE html>" +
                        "<html>" +
                        "<head><meta charset='UTF-8'><title>New Contact Form Submission</title></head>" +
                        "<body style='margin:0;padding:0;background:#f4f6f8;'>" +
                        "  <table width='100%' cellpadding='0' cellspacing='0'>" +
                        "    <tr>" +
                        "      <td align='center' style='padding:40px 0;'>" +
                        "        <table width='600' cellpadding='0' cellspacing='0' " +
                        "               style='background:#ffffff;border-radius:8px;" +
                        "                      box-shadow:0 4px 10px rgba(0,0,0,0.08);'>" +

                        "          <tr>" +
                        "            <td align='center' style='padding:30px;'>" +
                        "              <div style='font-size:28px;font-weight:bold;color:#2563eb;'>" +
                        "                🎓 S2DCMS" +
                        "              </div>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td style='padding:0 40px 20px 40px;font-family:Arial;color:#111;'>" +
                        "              <h2>New Contact Form Submission</h2>" +
                        "              <p style='font-size:14px;line-height:1.6;color:#555;'>" +
                        "                You have received a new message from the contact form." +
                        "              </p>" +
                        "              <p style='font-size:14px;line-height:1.6;color:#555;'>" +
                        "                <b>From:</b> " + senderName + " (" + senderEmail + ")" +
                        "              </p>" +
                        "              <p style='font-size:14px;line-height:1.6;color:#555;'>" +
                        "                <b>Message:</b><br/>" + message +
                        "              </p>" +
                        "            </td>" +
                        "          </tr>" +

                        "          <tr>" +
                        "            <td style='padding:30px;font-family:Arial;font-size:12px;" +
                        "                       color:#999;text-align:center;'>" +
                        "              © 2026 S2DCMS. All rights reserved." +
                        "            </td>" +
                        "          </tr>" +

                        "        </table>" +
                        "      </td>" +
                        "    </tr>" +
                        "  </table>" +
                        "</body>" +
                        "</html>";

        sendEmailWithReplyTo(toEmail, "New Contact Form Submission from " + senderName, htmlContent, senderEmail);
    }

    // =========================
    // CORE SENDER
    // =========================
    @CircuitBreaker(name = "brevo")
    @Retry(name = "brevo")
    private void sendEmail(String toEmail, String subject, String htmlContent) throws ApiException {

        SendSmtpEmailSender sender = new SendSmtpEmailSender()
                .email(senderEmail)
                .name(senderName);

        SendSmtpEmailTo to = new SendSmtpEmailTo()
                .email(toEmail);

        SendSmtpEmail email = new SendSmtpEmail()
                .sender(sender)
                .to(Collections.singletonList(to))
                .subject(subject)
                .htmlContent(htmlContent);

        emailApi.sendTransacEmail(email);
    }

    @CircuitBreaker(name = "brevo")
    @Retry(name = "brevo")
    private void sendEmailWithReplyTo(String toEmail, String subject, String htmlContent, String replyToEmail) throws ApiException {

        SendSmtpEmailSender sender = new SendSmtpEmailSender()
                .email(senderEmail)
                .name(senderName);

        SendSmtpEmailTo to = new SendSmtpEmailTo()
                .email(toEmail);

        SendSmtpEmailReplyTo replyTo = new SendSmtpEmailReplyTo()
                .email(replyToEmail);

        SendSmtpEmail email = new SendSmtpEmail()
                .sender(sender)
                .to(Collections.singletonList(to))
                .replyTo(replyTo)
                .subject(subject)
                .htmlContent(htmlContent);

        emailApi.sendTransacEmail(email);
    }
}
