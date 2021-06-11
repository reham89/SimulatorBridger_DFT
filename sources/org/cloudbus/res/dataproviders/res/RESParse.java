package org.cloudbus.res.dataproviders.res;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;

public class RESParse {
    ObjectMapper om=new ObjectMapper();

    public RESResponse parse(String filePath) throws IOException {
        return om.readValue(new File(filePath), RESResponse.class);
    }

}
