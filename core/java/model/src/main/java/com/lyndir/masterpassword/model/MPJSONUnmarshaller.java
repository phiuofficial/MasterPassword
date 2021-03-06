//==============================================================================
// This file is part of Master Password.
// Copyright (c) 2011-2017, Maarten Billemont.
//
// Master Password is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// Master Password is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You can find a copy of the GNU General Public License in the
// LICENSE file.  Alternatively, see <http://www.gnu.org/licenses/>.
//==============================================================================

package com.lyndir.masterpassword.model;

import com.google.gson.*;
import com.lyndir.masterpassword.MPMasterKey;
import com.lyndir.masterpassword.MPResultType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nonnull;


/**
 * @author lhunath, 2017-09-20
 */
public class MPJSONUnmarshaller implements MPUnmarshaller {

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter( MPMasterKey.Version.class, new EnumOrdinalAdapter() )
            .registerTypeAdapter( MPResultType.class, new MPResultTypeAdapter() )
            .setFieldNamingStrategy( FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES )
            .setPrettyPrinting().create();

    @Nonnull
    @Override
    public MPFileUser unmarshall(@Nonnull final File file)
            throws IOException, MPMarshalException {

        try (Reader reader = new InputStreamReader( new FileInputStream( file ), StandardCharsets.UTF_8 )) {
            return gson.fromJson( reader, MPJSONFile.class ).toUser();
        }
    }

    @Nonnull
    @Override
    public MPFileUser unmarshall(@Nonnull final String content)
            throws MPMarshalException {

        return gson.fromJson( content, MPJSONFile.class ).toUser();
    }
}
