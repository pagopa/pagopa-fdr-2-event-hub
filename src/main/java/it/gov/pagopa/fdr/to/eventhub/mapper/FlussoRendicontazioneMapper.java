package it.gov.pagopa.fdr.to.eventhub.mapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.modelmapper.spi.MappingContext;

import it.gov.pagopa.fdr.to.eventhub.model.FlussoRendicontazione;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.FlowTxEventModel;
import it.gov.pagopa.fdr.to.eventhub.model.eventhub.ReportedIUVEventModel;
import lombok.experimental.UtilityClass;

@UtilityClass
public class FlussoRendicontazioneMapper {

    private static final ModelMapper modelMapper = new ModelMapper();

    static {
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);

        // DateTimeFormatter definition with optional nanosecond support
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                .optionalStart()
                .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                .optionalEnd()
                .toFormatter();

        // flowtx converter
        modelMapper.addConverter((MappingContext<FlussoRendicontazione, FlowTxEventModel> context) -> {
            FlussoRendicontazione source = context.getSource();
            FlowTxEventModel destination = new FlowTxEventModel();

            destination.setIntPsp(source.getIdentificativoPSP());
            destination.setInsertedTimestamp(LocalDateTime.parse(source.getMetadata().get("InsertedTimestamp"), formatter));

            return destination;
        });

        // reportedIUV converter
        modelMapper.addConverter((MappingContext<FlussoRendicontazione, ReportedIUVEventModel> context) -> {
            FlussoRendicontazione source = context.getSource();
            ReportedIUVEventModel destination = new ReportedIUVEventModel();

            destination.setIntPsp(source.getIdentificativoPSP());
            destination.setInsertedTimestamp(LocalDateTime.parse(source.getMetadata().get("InsertedTimestamp"), formatter));

            return destination;
        });
    }

    /**
    * Maps a FlussoRendicontazione object to FlowTxEventModel.
    *
    * @param flux The FlussoRendicontazione object to map.
    * @return A mapped FlowTxEventModel object.
    */
    public static FlowTxEventModel toFlowTxEvent(FlussoRendicontazione flusso) {
        return modelMapper.map(flusso, FlowTxEventModel.class);
    }
    
    /**
     * Maps a FlussoRendicontazione object to toReportedIUVEvent.
     *
     * @param flux The FlussoRendicontazione object to map.
     * @return A mapped toReportedIUVEvent object.
     */
     public static ReportedIUVEventModel toReportedIUVEvent(FlussoRendicontazione flusso) {
         return modelMapper.map(flusso, ReportedIUVEventModel.class);
     }
}
