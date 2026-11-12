package com.example.demo.controller;

import com.example.demo.service.OutboundClickService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/** 集計の閲覧権限は、既存の/admin/**に対するADMIN制限で確認する。 */
@Controller
public class OutboundClickAdminController {
    private final OutboundClickService clicks;

    public OutboundClickAdminController(OutboundClickService clicks) {
        this.clicks = clicks;
    }

    @GetMapping("/admin/outbound-clicks")
    public ModelAndView report(@RequestParam(defaultValue = "0") int page) {
        var view = new ModelAndView("admin/outbound-clicks");
        try {
            view.addObject("summary", clicks.summary());
            view.addObject("accounts", clicks.accounts(page));
        } catch (DataAccessException | TransactionException ex) {
            view.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
            view.addObject("unavailable", true);
        }
        return view;
    }
}
