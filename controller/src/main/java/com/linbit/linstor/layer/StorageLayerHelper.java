package com.linbit.linstor.layer;

import com.linbit.ExhaustedPoolException;
import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.ValueInUseException;
import com.linbit.ValueOutOfRangeException;
import com.linbit.linstor.LinStorException;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.Volume;
import com.linbit.linstor.VolumeDefinition;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.numberpool.DynamicNumberPool;
import com.linbit.linstor.numberpool.NumberPoolModule;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.data.provider.StorageRscData;
import com.linbit.linstor.storage.data.provider.swordfish.SfVlmDfnData;
import com.linbit.linstor.storage.interfaces.categories.resource.RscDfnLayerObject;
import com.linbit.linstor.storage.interfaces.categories.resource.RscLayerObject;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmDfnLayerObject;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmProviderObject;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;
import com.linbit.linstor.storage.utils.LayerDataFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.sql.SQLException;

@Singleton
class StorageLayerHelper extends AbsLayerHelper<StorageRscData, VlmProviderObject, RscDfnLayerObject, VlmDfnLayerObject>
{
    @Inject
    StorageLayerHelper(
        ErrorReporter errorReporterRef,
        @ApiContext AccessContext apiCtxRef,
        LayerDataFactory layerDataFactoryRef,
        @Named(NumberPoolModule.LAYER_RSC_ID_POOL)  DynamicNumberPool layerRscIdPoolRef,
        Provider<CtrlLayerDataHelper> layerHelperProviderRef
    )
    {
        super(
            errorReporterRef,
            apiCtxRef,
            layerDataFactoryRef,
            layerRscIdPoolRef,
            StorageRscData.class,
            DeviceLayerKind.STORAGE,
            layerHelperProviderRef
        );
    }

    @Override
    protected RscDfnLayerObject createRscDfnData(
        ResourceDefinition rscDfnRef,
        String rscNameSuffixRef,
        LayerPayload payloadRef
    )
    {
        // StorageLayer does not have resource-definition specific data
        return null;
    }

    @Override
    protected void mergeRscDfnData(RscDfnLayerObject rscDfnRef, LayerPayload payloadRef)
    {
        // no Storage specific resource-definition, nothing to merge
    }

    @Override
    protected VlmDfnLayerObject createVlmDfnData(
        VolumeDefinition vlmDfnRef,
        String rscNameSuffixRef,
        LayerPayload payloadRef
    )
    {
        // StorageLayer does not have volume-definition specific data
        return null;
    }

    @Override
    protected void mergeVlmDfnData(VlmDfnLayerObject vlmDfnDataRef, LayerPayload payloadRef)
    {
        // no Storage specific volume-definition, nothing to merge
    }

    @Override
    protected StorageRscData createRscData(
        Resource rscRef,
        LayerPayload payloadRef,
        String rscNameSuffixRef,
        RscLayerObject parentObjectRef
    )
        throws AccessDeniedException, SQLException, ValueOutOfRangeException, ExhaustedPoolException,
            ValueInUseException
    {
        return layerDataFactory.createStorageRscData(
            layerRscIdPool.autoAllocate(),
            parentObjectRef,
            rscRef,
            rscNameSuffixRef
        );
    }

    @Override
    protected void mergeRscData(StorageRscData rscDataRef, LayerPayload payloadRef)
    {
        // nothing to merge
    }

    @Override
    protected boolean needsChildVlm(RscLayerObject childRscDataRef, Volume vlmRef)
        throws AccessDeniedException, InvalidKeyException
    {
        throw new ImplementationError("Storage layer should not have child volumes to be asked for");
    }

    @Override
    protected VlmProviderObject createVlmLayerData(
        StorageRscData rscData,
        Volume vlm,
        LayerPayload payload
    )
        throws AccessDeniedException, SQLException, ValueOutOfRangeException, ExhaustedPoolException,
            ValueInUseException, LinStorException, InvalidKeyException, InvalidNameException
    {
        StorPool storPool = layerDataHelperProvider.get().getStorPool(vlm, rscData);

        DeviceProviderKind kind = storPool.getDeviceProviderKind();
        VlmProviderObject vlmData = rscData.getVlmProviderObject(vlm.getVolumeDefinition().getVolumeNumber());
        if (vlmData == null)
        {
            switch (kind)
            {
                case SWORDFISH_INITIATOR:
                    {
                        SfVlmDfnData vlmDfnData = ensureSfVlmDfnExists(
                            vlm.getVolumeDefinition(),
                            rscData.getResourceNameSuffix()
                        );
                        vlmData = layerDataFactory.createSfInitData(
                            vlm,
                            rscData,
                            vlmDfnData,
                            storPool
                        );
                    }
                    break;
                case SWORDFISH_TARGET:
                    {
                        SfVlmDfnData vlmDfnData = ensureSfVlmDfnExists(
                            vlm.getVolumeDefinition(),
                            rscData.getResourceNameSuffix()
                        );
                        vlmData = layerDataFactory.createSfTargetData(
                            vlm,
                            rscData,
                            vlmDfnData,
                            storPool
                        );
                    }
                    break;
                case DISKLESS:
                    vlmData = layerDataFactory.createDisklessData(
                        vlm,
                        vlm.getVolumeDefinition().getVolumeSize(apiCtx),
                        rscData,
                        storPool
                    );
                    break;
                case LVM:
                    vlmData = layerDataFactory.createLvmData(vlm, rscData, storPool);
                    break;
                case LVM_THIN:
                    vlmData = layerDataFactory.createLvmThinData(vlm, rscData, storPool);
                    break;
                case ZFS: // fall-through
                case ZFS_THIN:
                    vlmData = layerDataFactory.createZfsData(vlm, rscData, kind, storPool);
                    break;
                case FAIL_BECAUSE_NOT_A_VLM_PROVIDER_BUT_A_VLM_LAYER: // fall-through
                default:
                    throw new ImplementationError("Unexpected kind: " + kind);
            }
            storPool.putVolume(apiCtx, vlmData);
        }
        return vlmData;
    }

    @Override
    protected void mergeVlmData(VlmProviderObject vlmDataRef, Volume vlmRef, LayerPayload payloadRef)
        throws AccessDeniedException, InvalidKeyException, InvalidNameException, SQLException
    {
        // if storage pool changed (i.e. because of a toggle disk) we need to update that

        StorPool currentStorPool = vlmDataRef.getStorPool();

        StorPool newStorPool = getStorPool(vlmRef, vlmDataRef.getRscLayerObject());
        if (newStorPool != null && !newStorPool.equals(currentStorPool))
        {
            vlmDataRef.setStorPool(apiCtx, newStorPool);
        }
    }

    private SfVlmDfnData ensureSfVlmDfnExists(
        VolumeDefinition vlmDfn,
        String rscNameSuffix
    )
        throws AccessDeniedException, SQLException
    {
        VlmDfnLayerObject vlmDfnData = vlmDfn.getLayerData(
            apiCtx,
            DeviceLayerKind.STORAGE,
            rscNameSuffix
        );
        if (vlmDfnData == null)
        {
            vlmDfnData = layerDataFactory.createSfVlmDfnData(
                vlmDfn,
                null,
                rscNameSuffix
            );
            vlmDfn.setLayerData(apiCtx, vlmDfnData);
        }
        if (!(vlmDfnData instanceof SfVlmDfnData))
        {
            throw new ImplementationError(
                "Unexpected type of volume definition storage data: " +
                    vlmDfnData.getClass().getSimpleName()
            );
        }
        return (SfVlmDfnData) vlmDfnData;
    }
}
