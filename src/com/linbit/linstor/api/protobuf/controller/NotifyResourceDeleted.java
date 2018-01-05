/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.linbit.linstor.api.protobuf.controller;

import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.protobuf.BaseProtoApiCall;
import com.linbit.linstor.api.protobuf.ProtobufApiCall;
import com.linbit.linstor.core.Controller;
import com.linbit.linstor.netcom.Message;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.proto.MsgDelRscOuterClass;
import com.linbit.linstor.security.AccessContext;
import java.io.IOException;
import java.io.InputStream;

/**
 *
 * @author rpeinthor
 */
@ProtobufApiCall
public class NotifyResourceDeleted extends BaseProtoApiCall {
    private final Controller controller;

    public NotifyResourceDeleted(Controller controllerRef)
    {
        super(controllerRef.getErrorReporter());
        controller = controllerRef;
    }

    @Override
    public String getName()
    {
        return InternalApiConsts.API_NOTIFY_RSC_DEL;
    }

    @Override
    public String getDescription()
    {
        return "Notify controller that the satellite deleted the resource.";
    }

    @Override
    public void executeImpl(
        AccessContext accCtx,
        Message msg,
        int msgId,
        InputStream msgDataIn,
        Peer client
    )
        throws IOException
    {
        MsgDelRscOuterClass.MsgDelRsc msgDeleteRsc = MsgDelRscOuterClass.MsgDelRsc.parseDelimitedFrom(msgDataIn);
        ApiCallRc apiCallRc = controller.getApiCallHandler().resourceDeleted(
            accCtx,
            client,
            msgDeleteRsc.getNodeName(),
            msgDeleteRsc.getRscName()
        );

        super.answerApiCallRc(accCtx, client, msgId, apiCallRc);
    }
}