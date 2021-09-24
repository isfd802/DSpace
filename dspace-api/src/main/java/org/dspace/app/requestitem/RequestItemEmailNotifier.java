/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
/*
 * Copyright 2021 Indiana University.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.dspace.app.requestitem;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import javax.mail.MessagingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.requestitem.factory.RequestItemServiceFactory;
import org.dspace.app.requestitem.service.RequestItemService;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.core.I18nUtil;
import org.dspace.core.LogHelper;
import org.dspace.handle.factory.HandleServiceFactory;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;

/**
 * Send item requests and responses by email.
 *
 * @author Mark H. Wood <mwood@iupui.edu>
 */
public class RequestItemEmailNotifier {
    private static final Logger LOG = LogManager.getLogger();

    private static final BitstreamService bitstreamService
            = ContentServiceFactory.getInstance().getBitstreamService();

    private static final ConfigurationService configurationService
            = DSpaceServicesFactory.getInstance().getConfigurationService();

    private static final HandleService handleService
            = HandleServiceFactory.getInstance().getHandleService();

    private static final RequestItemService requestItemService
            = RequestItemServiceFactory.getInstance().getRequestItemService();

    private static final RequestItemAuthorExtractor requestItemAuthorExtractor
            = DSpaceServicesFactory.getInstance()
                    .getServiceManager()
                    .getServiceByName(null, RequestItemAuthorExtractor.class);

    private RequestItemEmailNotifier() {}

    /**
     * Send the request to the approver(s).
     *
     * @param context current DSpace session.
     * @param ri the request.
     * @param responseLink link back to DSpace to send the response.
     * @throws IOException passed through.
     * @throws SQLException if the message was not sent.
     */
    static public void sendRequest(Context context, RequestItem ri, String responseLink)
            throws IOException, SQLException {
        // Who is making this request?
        RequestItemAuthor author = requestItemAuthorExtractor
                .getRequestItemAuthor(context, ri.getItem());
        String authorEmail = author.getEmail();
        String authorName = author.getFullName();

        // Build an email to the approver.
        Email email = Email.getEmail(I18nUtil.getEmailFilename(context.getCurrentLocale(),
                "request_item.author"));
        email.addRecipient(authorEmail);
        email.setReplyTo(ri.getReqEmail()); // Requester's address
        email.addArgument(ri.getReqName()); // {0} Requester's name
        email.addArgument(ri.getReqEmail()); // {1} Requester's address
        email.addArgument(ri.isAllfiles() // {2} All bitstreams or just one?
            ? I18nUtil.getMessage("itemRequest.all") : ri.getBitstream().getName());
        email.addArgument(handleService.getCanonicalForm(ri.getItem().getHandle()));
        email.addArgument(ri.getItem().getName()); // {4} requested item's title
        email.addArgument(ri.getReqMessage()); // {5} message from requester
        email.addArgument(responseLink); // {6} Link back to DSpace for action
        email.addArgument(authorName); // {7} corresponding author name
        email.addArgument(authorEmail); // {8} corresponding author email
        email.addArgument(configurationService.getProperty("dspace.name"));
        email.addArgument(configurationService.getProperty("mail.helpdesk"));

        // Send the email.
        try {
            email.send();
            Bitstream bitstream = ri.getBitstream();
            String bitstreamID;
            if (null == bitstream) {
                bitstreamID = "null";
            } else {
                bitstreamID = ri.getBitstream().getID().toString();
            }
            LOG.info(LogHelper.getHeader(context,
                    "sent_email_requestItem",
                    "submitter_id={},bitstream_id={},requestEmail={}"),
                    ri.getReqEmail(), bitstreamID, ri.getReqEmail());
        } catch (MessagingException e) {
            LOG.warn(LogHelper.getHeader(context,
                    "error_mailing_requestItem", e.getMessage()));
            throw new IOException("Request not sent:  " + e.getMessage());
        }
    }

    /**
     * Send the approver's response back to the requester, with files attached
     * if approved.
     *
     * @param context current DSpace session.
     * @param ri the request.
     * @param subject email subject header value.
     * @param message email body (may be empty).
     * @throws IOException if sending failed.
     */
    static public void sendResponse(Context context, RequestItem ri, String subject,
            String message)
            throws IOException {
        // Build an email back to the requester.
        Email email = new Email();
        email.setContent("body", message);
        email.setSubject(subject);
        email.addRecipient(ri.getReqEmail());
        if (ri.isAccept_request()) {
            // Attach bitstreams.
            try {
                if (ri.isAllfiles()) {
                    Item item = ri.getItem();
                    List<Bundle> bundles = item.getBundles("ORIGINAL");
                    for (Bundle bundle : bundles) {
                        List<Bitstream> bitstreams = bundle.getBitstreams();
                        for (Bitstream bitstream : bitstreams) {
                            if (!bitstream.getFormat(context).isInternal() &&
                                    requestItemService.isRestricted(context,
                                    bitstream)) {
                                email.addAttachment(bitstreamService.retrieve(context,
                                        bitstream), bitstream.getName(),
                                        bitstream.getFormat(context).getMIMEType());
                            }
                        }
                    }
                } else {
                    Bitstream bitstream = ri.getBitstream();
                    email.addAttachment(bitstreamService.retrieve(context, bitstream),
                            bitstream.getName(),
                            bitstream.getFormat(context).getMIMEType());
                }
                email.send();
            } catch (MessagingException | IOException | SQLException | AuthorizeException e) {
                LOG.warn(LogHelper.getHeader(context,
                        "error_mailing_requestItem", e.getMessage()));
                throw new IOException("Reply not sent:  " + e.getMessage());
            }
        }
        LOG.info(LogHelper.getHeader(context,
                "sent_attach_requestItem", "token={}"), ri.getToken());
    }
}
